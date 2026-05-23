package com.wyspr.feature.messaging.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wyspr.feature.messaging.audio.AudioRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun rememberVoiceRecordController(
    onConfirmed: (audioBytes: ByteArray, durationMs: Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val recorder = remember { AudioRecorder(context) }

    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (recorder.start()) {
                recording = true
                elapsedMs = 0L
            } else {
                error = "Couldn't start recording."
            }
        } else {
            permissionDenied = true
        }
    }

    if (recording) {
        LaunchedEffect(Unit) {
            while (isActive) {
                delay(200)
                elapsedMs = recorder.elapsedMs
                if (elapsedMs >= AudioRecorder.MAX_DURATION_MS) {
                    val result = recorder.stop()
                    recording = false
                    if (result != null) {
                        val bytes = result.file.readBytes()
                        result.file.delete()
                        onConfirmed(bytes, result.durationMs)
                    }
                    return@LaunchedEffect
                }
            }
        }

        val sec = (elapsedMs / 1000).coerceAtLeast(0)
        val min = sec / 60
        val s = sec % 60

        AlertDialog(
            onDismissRequest = {
                recorder.cancel()
                recording = false
            },
            title = { Text("Recording…") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "%d:%02d".format(min, s),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Tap Send to stop and send, or Cancel to discard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = recorder.stop()
                    recording = false
                    if (result != null) {
                        val bytes = result.file.readBytes()
                        result.file.delete()
                        onConfirmed(bytes, result.durationMs)
                    } else {
                        error = "Recording was too short."
                    }
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = {
                    recorder.cancel()
                    recording = false
                }) { Text("Cancel") }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't record") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            title = { Text("Microphone access needed") },
            text = { Text("Grant microphone permission in Settings to send voice notes.") },
            confirmButton = {
                TextButton(onClick = { permissionDenied = false }) { Text("OK") }
            },
        )
    }

    return {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            if (recorder.start()) {
                recording = true
                elapsedMs = 0L
            } else {
                error = "Couldn't start recording."
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
