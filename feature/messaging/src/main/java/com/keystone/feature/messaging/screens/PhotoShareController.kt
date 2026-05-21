package com.keystone.feature.messaging.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keystone.feature.messaging.image.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose helper that wires the system photo picker, the [ImageCompressor]
 * pipeline, and a short modal so the user sees compression progress.
 * Returns a `() -> Unit` the composer button calls — same shape as
 * [rememberLocationShareController] for symmetry.
 *
 * The picker uses `PickVisualMedia.ImageOnly` which on Android 13+
 * opens the system photo picker (no permission required) and falls
 * back to a legacy launcher on older versions (still no
 * READ_EXTERNAL_STORAGE needed when going through this contract).
 *
 * On success [onConfirmed] is invoked with the JPEG bytes ready to
 * hand to a viewmodel's `sendImage`. On any compression failure the
 * user gets a short error dialog and the message is not sent.
 */
@Composable
fun rememberPhotoShareController(
    onConfirmed: (jpegBytes: ByteArray) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var compressing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            compressing = true
            val bytes = withContext(Dispatchers.IO) {
                ImageCompressor.compress(context.contentResolver, uri)
            }
            compressing = false
            if (bytes != null) {
                onConfirmed(bytes)
            } else {
                error = "Couldn't shrink that photo enough to send. " +
                    "Try a smaller or simpler image."
            }
        }
    }

    if (compressing) {
        AlertDialog(
            onDismissRequest = { /* mid-compress, blocked */ },
            title = { Text("Preparing photo…") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Shrinking to fit the wire budget.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
        )
    }
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't send photo") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }

    return {
        launcher.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
        )
    }
}
