package com.wyspr.feature.messaging.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.wyspr.feature.messaging.file.FilePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberFileShareController(
    onConfirmed: (fileName: String, mimeType: String, fileBytes: ByteArray) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                    var fileName = "file"
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameIdx >= 0) {
                            fileName = cursor.getString(nameIdx) ?: "file"
                        }
                    }
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext null
                    if (bytes.size > FilePayload.MAX_RAW_BYTES) {
                        return@withContext "too_large" to bytes.size
                    }
                    Triple(fileName, mimeType, bytes)
                } catch (e: Exception) {
                    null
                }
            }
            when (result) {
                is Triple<*, *, *> -> {
                    val name = result.first as String
                    val mime = result.second as String
                    val bytes = result.third as ByteArray
                    onConfirmed(name, mime, bytes)
                }
                is Pair<*, *> -> {
                    error = "File too large (${FilePayload.formatSize((result.second as Int).toLong())}). " +
                        "Maximum is ${FilePayload.formatSize(FilePayload.MAX_RAW_BYTES.toLong())}."
                }
                else -> {
                    error = "Couldn't read that file."
                }
            }
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't send file") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }

    return {
        launcher.launch(arrayOf("*/*"))
    }
}
