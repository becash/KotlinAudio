package com.example.kotlin_audio_example.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
@ExperimentalMaterial3Api
fun ActionBottomSheet(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartSync: () -> Unit,
) {
    val modalBottomSheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        sheetState = modalBottomSheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        InnerSheet(
            onOpenSettings = {
                onDismiss()
                onOpenSettings()
            },
            onStartSync = {
                onDismiss()
                onStartSync()
            }
        )
    }
}

@Composable
fun InnerSheet(
    onOpenSettings: () -> Unit = {},
    onStartSync: () -> Unit = {},
) {
    Button(
        onClick = onOpenSettings,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
    ) {
        Text("Setări Nextcloud")
    }

    Button(
        onClick = onStartSync,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary
        ),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
    ) {
        Text("Sincronizează din Nextcloud")
    }
}

@Preview
@ExperimentalMaterial3Api
@Composable
fun ActionBottomSheetPreview() {
    InnerSheet()
}