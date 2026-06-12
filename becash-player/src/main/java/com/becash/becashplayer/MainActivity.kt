package com.becash.becashplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.becash.becashplayer.ui.Screen
import com.becash.becashplayer.ui.screen.MainScreen
import com.becash.becashplayer.ui.screen.SettingsScreen
import com.becash.becashplayer.ui.theme.BecashPlayerTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    private val requiredPermissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.loadAndPlay()
        } else {
            Toast.makeText(this, "Permisiunea de stocare este necesară.", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingCallNumber: String? = null
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCallNumber?.let { startActivity(Intent(Intent.ACTION_CALL, "tel:$it".toUri())) }
        } else {
            Toast.makeText(this, "Permisiunea de apel este necesară.", Toast.LENGTH_LONG).show()
        }
        pendingCallNumber = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (hasStoragePermission()) {
            viewModel.loadAndPlay()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            BecashPlayerTheme {
                when (viewModel.currentScreen) {
                    is Screen.Settings -> SettingsScreen(
                        settings = viewModel.appSettings,
                        onBack = { viewModel.currentScreen = Screen.Main }
                    )
                    is Screen.Main -> MainScreen(vm = viewModel, onCallPhone = ::callPhone)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveState()
    }

    private fun callPhone(number: String) {
        if (number.isBlank()) return
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(Intent.ACTION_CALL, "tel:$number".toUri()))
        } else {
            pendingCallNumber = number
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun hasStoragePermission(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
