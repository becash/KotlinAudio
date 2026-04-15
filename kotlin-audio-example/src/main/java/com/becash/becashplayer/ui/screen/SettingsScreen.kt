package com.becash.becashplayer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.becash.becashplayer.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }
    var remoteFolderPath by remember { mutableStateOf(settings.remoteFolderPath) }
    var localFolderName by remember { mutableStateOf(settings.localFolderName) }
    var mysqlHost by remember { mutableStateOf(settings.mysqlHost) }
    var mysqlPort by remember { mutableStateOf(settings.mysqlPort.toString()) }
    var mysqlUser by remember { mutableStateOf(settings.mysqlUser) }
    var mysqlPassword by remember { mutableStateOf(settings.mysqlPassword) }
    var mysqlDatabase by remember { mutableStateOf(settings.mysqlDatabase) }
    var passwordVisible by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = "Setări Nextcloud",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Înapoi",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "Conexiune Nextcloud (WebDAV)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("URL Server") },
                    placeholder = { Text("https://cloud.exemplu.ro") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Utilizator") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Parolă") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Ascunde parola" else "Arată parola"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Sincronizare",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = remoteFolderPath,
                    onValueChange = { remoteFolderPath = it },
                    label = { Text("Folder remote Nextcloud") },
                    placeholder = { Text("/Music") },
                    supportingText = { Text("Calea din Nextcloud de sincronizat recursiv") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = localFolderName,
                    onValueChange = { localFolderName = it },
                    label = { Text("Folder local pe telefon") },
                    placeholder = { Text("Audio") },
                    supportingText = { Text("Creat în /sdcard/{folder}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Bază de date MySQL",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = mysqlHost,
                    onValueChange = { mysqlHost = it },
                    label = { Text("Host MySQL") },
                    placeholder = { Text("c.ci.md") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = mysqlPort,
                    onValueChange = { mysqlPort = it },
                    label = { Text("Port MySQL") },
                    placeholder = { Text("3306") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = mysqlUser,
                    onValueChange = { mysqlUser = it },
                    label = { Text("Utilizator MySQL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = mysqlPassword,
                    onValueChange = { mysqlPassword = it },
                    label = { Text("Parolă MySQL") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = mysqlDatabase,
                    onValueChange = { mysqlDatabase = it },
                    label = { Text("Bază de date") },
                    placeholder = { Text("becash_player") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        settings.serverUrl = serverUrl.trimEnd('/')
                        settings.username = username.trim()
                        settings.password = password
                        settings.remoteFolderPath = remoteFolderPath.trim().let {
                            if (it.startsWith('/')) it else "/$it"
                        }
                        settings.localFolderName = localFolderName.trim().ifEmpty { "Audio" }
                        settings.mysqlHost = mysqlHost.trim()
                        settings.mysqlPort = mysqlPort.trim().toIntOrNull() ?: 3306
                        settings.mysqlUser = mysqlUser.trim()
                        settings.mysqlPassword = mysqlPassword
                        settings.mysqlDatabase = mysqlDatabase.trim().ifEmpty { "becash_player" }
                        savedMessage = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salvează setările")
                }

                if (savedMessage) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Setările au fost salvate.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}