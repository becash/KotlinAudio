package com.example.kotlin_audio_example

import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class WebDavEntry(val href: String, val isDirectory: Boolean)

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val current: Int, val total: Int, val currentFile: String) : SyncState()
    data class Done(val downloaded: Int, val skipped: Int) : SyncState()
    data class Error(val message: String) : SyncState()
}

object WebDavSync {

    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
    private const val MAX_RETRIES = 3

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun basicAuthHeader(username: String, password: String): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Extrage mereu doar protocol://host:port din serverUrl,
     * indiferent dacă userul a pus sau nu calea WebDAV în URL.
     */
    private fun extractBaseUrl(serverUrl: String): String {
        val u = URL(serverUrl)
        return "${u.protocol}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
    }

    /**
     * Construiește URL-ul WebDAV Nextcloud.
     * Funcționează corect indiferent dacă serverUrl conține sau nu /remote.php/...
     */
    private fun buildWebDavUrl(serverUrl: String, username: String, remotePath: String): String {
        val base = extractBaseUrl(serverUrl)
        val path = remotePath.trimStart('/')
        return "$base/remote.php/dav/files/$username/$path"
    }

    /**
     * Listează recursiv toate fișierele audio dintr-un folder WebDAV (PROPFIND Depth: infinity).
     * Returnează lista de URL-uri complete ale fișierelor audio găsite.
     */
    suspend fun listAudioFiles(
        serverUrl: String,
        username: String,
        password: String,
        remotePath: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val davUrl = buildWebDavUrl(serverUrl, username, remotePath)
            Timber.d("PROPFIND: $davUrl")

            val propfindBody = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontenttype/>
                  </D:prop>
                </D:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(davUrl)
                .method(
                    "PROPFIND",
                    propfindBody.toRequestBody("application/xml".toMediaType())
                )
                .header("Authorization", basicAuthHeader(username, password))
                .header("Depth", "infinity")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (responseCode !in 200..299 && responseCode != 207) {
                return@withContext Result.failure(
                    Exception("Server a răspuns cu codul: $responseCode")
                )
            }

            val entries = parsePropfindResponse(responseBody)
            val baseServerPath = extractBaseUrl(serverUrl)

            val audioFiles = entries
                .filter { !it.isDirectory }
                .filter { it.href.substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS }
                .map { entry ->
                    if (entry.href.startsWith("http")) entry.href
                    else "$baseServerPath${entry.href}"
                }

            Timber.d("Găsite ${audioFiles.size} fișiere audio.")
            Result.success(audioFiles)
        } catch (e: Exception) {
            Timber.e(e, "Eroare la listarea WebDAV")
            Result.failure(e)
        }
    }

    /**
     * Descarcă un singur fișier WebDAV în folderul local.
     * Download atomic: fișier temporar → rename.
     * Încearcă de MAX_RETRIES ori cu pauze exponențiale la erori de rețea.
     */
    private fun downloadFile(
        fileUrl: String,
        username: String,
        password: String,
        localFile: File
    ): Boolean {
        val tmpFile = File(localFile.parent, "${localFile.name}.tmp")
        localFile.parentFile?.mkdirs()

        repeat(MAX_RETRIES) { attempt ->
            try {
                val request = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", basicAuthHeader(username, password))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("Download eșuat (HTTP ${response.code}) pentru ${localFile.name}")
                    response.close()
                    return false  // eroare HTTP → nu reîncercăm
                }

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                response.close()
                tmpFile.renameTo(localFile)
                Timber.d("Descărcat: ${localFile.name}")
                return true

            } catch (e: Exception) {
                tmpFile.delete()
                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = 1500L * (attempt + 1)
                    Timber.w("Reîncercare ${attempt + 1}/$MAX_RETRIES pentru ${localFile.name} după ${delayMs}ms (${e.message})")
                    Thread.sleep(delayMs)
                } else {
                    Timber.e(e, "Eroare la descărcarea: $fileUrl")
                }
            }
        }
        return false
    }

    /**
     * Sincronizare completă: listează remote, descarcă fișierele lipsă local.
     */
    suspend fun sync(
        settings: AppSettings,
        localDir: File,
        onProgress: (SyncState) -> Unit
    ) = withContext(Dispatchers.IO) {
        val listResult = listAudioFiles(
            serverUrl = settings.serverUrl,
            username = settings.username,
            password = settings.password,
            remotePath = settings.remoteFolderPath
        )

        if (listResult.isFailure) {
            onProgress(SyncState.Error("Nu s-a putut conecta: ${listResult.exceptionOrNull()?.message}"))
            return@withContext
        }

        val remoteFiles = listResult.getOrThrow()
        if (remoteFiles.isEmpty()) {
            onProgress(SyncState.Done(downloaded = 0, skipped = 0))
            return@withContext
        }

        if (!localDir.exists()) localDir.mkdirs()

        var downloaded = 0
        var skipped = 0

        remoteFiles.forEachIndexed { index, fileUrl ->
            val fileName = try {
                URLDecoder.decode(fileUrl.substringAfterLast('/'), "UTF-8")
            } catch (_: Exception) {
                fileUrl.substringAfterLast('/')
            }
            val localFile = File(localDir, fileName)

            onProgress(
                SyncState.Syncing(
                    current = index + 1,
                    total = remoteFiles.size,
                    currentFile = fileName
                )
            )

            if (localFile.exists()) {
                Timber.d("Sărit (există deja): $fileName")
                skipped++
            } else {
                val ok = downloadFile(fileUrl, settings.username, settings.password, localFile)
                if (ok) downloaded++ else skipped++
            }
        }

        onProgress(SyncState.Done(downloaded = downloaded, skipped = skipped))
    }

    // -------------------------------------------------------------------------
    // XML parsing pentru răspunsul PROPFIND
    // -------------------------------------------------------------------------

    private fun parsePropfindResponse(xml: String): List<WebDavEntry> {
        val entries = mutableListOf<WebDavEntry>()
        var currentHref = StringBuilder()
        var isCollection = false
        var inResponse = false
        var inHref = false
        var inResourceType = false

        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val localName = when (eventType) {
                    XmlPullParser.START_TAG, XmlPullParser.END_TAG ->
                        parser.name.substringAfterLast(':').lowercase()
                    else -> ""
                }

                when (eventType) {
                    XmlPullParser.START_TAG -> when (localName) {
                        "response" -> {
                            inResponse = true
                            currentHref.clear()
                            isCollection = false
                        }
                        "href" -> if (inResponse) inHref = true
                        "resourcetype" -> inResourceType = true
                        "collection" -> if (inResourceType) isCollection = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inHref && inResponse) currentHref.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> when (localName) {
                        "response" -> {
                            val href = currentHref.toString().trim()
                            if (inResponse && href.isNotEmpty()) {
                                entries.add(WebDavEntry(href, isCollection))
                            }
                            inResponse = false
                        }
                        "href" -> inHref = false
                        "resourcetype" -> inResourceType = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Timber.e(e, "Eroare la parsarea XML PROPFIND")
        }

        return entries
    }
}
