package com.becash.becashplayer

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.becash.becashplayer.ext.millisecondsToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Dialog care afișează detalii complete despre cântecul curent:
 *  - informații fișier (mărime, format, cale)
 *  - statistici din playlist.json (redate, listen, completitudine, shuffle weight etc.)
 *  - tag-uri ID3 citite din fișier
 */
@Composable
fun TrackInfoDialog(
    audioUrl: String,
    songInfo: JSONObject?,
    onDismiss: () -> Unit,
) {
    val filePath = audioUrl.removePrefix("file://")
    val file = File(filePath)

    // null = se încarcă, emptyList = lipsesc tag-uri, non-empty = tag-uri găsite
    var id3Tags by remember(audioUrl) { mutableStateOf<List<Pair<String, String>>?>(null) }

    LaunchedEffect(audioUrl) {
        id3Tags = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                buildList {
                    fun tryAdd(key: Int, label: String, transform: (String) -> String = { it }) {
                        retriever.extractMetadata(key)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { add(label to transform(it)) }
                    }
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_TITLE,        "Titlu")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_ARTIST,       "Artist")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,  "Artist album")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_AUTHOR,       "Autor")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_COMPOSER,     "Compozitor")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_WRITER,       "Versuri")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_ALBUM,        "Album")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_YEAR,         "An")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, "Track nr.")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS,   "Nr. piese album")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER,  "Disc nr.")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_GENRE,        "Gen muzical")
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_BITRATE, "Bitrate") {
                        it.toLongOrNull()?.let { bps -> "${bps / 1000} kbps" } ?: it
                    }
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_DURATION, "Durată (tag)") {
                        it.toLongOrNull()?.millisecondsToString() ?: it
                    }
                    tryAdd(MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "MIME type")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        tryAdd(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "Sample rate") {
                            it.toIntOrNull()?.let { hz -> "$hz Hz" } ?: it
                        }
                        tryAdd(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE, "Biți/mostră") {
                            it.toIntOrNull()?.let { b -> "$b bit" } ?: it
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                retriever.release()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {

                Text(
                    text = file.nameWithoutExtension,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ── Fișier ────────────────────────────────────────────────
                    InfoSection("Fișier") {
                        InfoRow("Mărime", formatFileSize(file.length()))
                        InfoRow("Format", file.extension.uppercase())
                        InfoRow("Cale",   file.absolutePath)
                    }

                    // ── Statistici (din playlist.json / MySQL) ────────────────
                    if (songInfo != null) {
                        InfoSection("Statistici") {
                            val plays = songInfo.optInt("plays", 0)
                            if (plays > 0) InfoRow("Redate", "$plays ori")

                            val listen = songInfo.optLong("listen", 0L)
                            if (listen > 0) InfoRow("Timp ascultat", listen.millisecondsToString())

                            val dur = songInfo.optLong("duration", 0L)
                            if (dur > 0) InfoRow("Durată înregistrată", dur.millisecondsToString())

                            val rate = songInfo.optInt("rate", 0)
                            if (rate > 0) InfoRow("Apreciere", "$rate / 5")

                            val dance = songInfo.opt("dance")
                                .let { it == true || it == 1 || it?.toString() == "1" }
                            if (dance) InfoRow("Muzică dans", "da")

                            val completeness = songInfo.optDouble("completeness", -1.0)
                            if (completeness >= 0)
                                InfoRow("Completitudine", "${"%.0f".format(completeness * 100)} %")

                            val weight = songInfo.optDouble("shuffle_weight", -1.0)
                            if (weight > 0)
                                InfoRow("Prioritate shuffle", formatWeight(weight))

                            val updated = songInfo.optString("updated")
                                .takeIf { it.isNotBlank() && it != "null" }
                            if (updated != null)
                                InfoRow("Ultima redare", updated.substringBefore(".").trim())
                        }
                    }

                    // ── ID3 Tags ──────────────────────────────────────────────
                    when {
                        id3Tags == null -> {
                            InfoSection("ID3 Tags") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                        id3Tags!!.isNotEmpty() -> {
                            InfoSection("ID3 Tags") {
                                id3Tags!!.forEach { (label, value) ->
                                    InfoRow(label, value)
                                }
                            }
                        }
                        // emptyList → nu există tag-uri, secțiunea nu se afișează
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 6.dp)
                ) {
                    Text("Închide")
                }
            }
        }
    }
}

// ── Composable helpers ─────────────────────────────────────────────────────

@Composable
private fun InfoSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 3.dp)
    )
    Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    Spacer(modifier = Modifier.height(4.dp))
    content()
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.62f),
            softWrap = true
        )
    }
}

// ── Pure helpers ───────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000L -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
    bytes >= 1_000L     -> "${bytes / 1_000} KB"
    else                -> "$bytes B"
}

private fun formatWeight(weight: Double): String = when {
    weight >= 1_000_000.0 -> "%.2e".format(weight)
    weight >= 1_000.0     -> "%.0f".format(weight)
    else                  -> "%.1f".format(weight)
}
