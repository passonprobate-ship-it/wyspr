package com.wyspr.feature.messaging.file

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@Composable
internal fun FileBubble(
    body: String,
    fromSelf: Boolean,
) {
    val decoded = remember(body) { FilePayload.decode(body) }
    if (decoded == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("File", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val context = LocalContext.current
    val textColor = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val subtextColor = (if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f)

    Row(
        modifier = Modifier.clickable {
            try {
                val dir = File(context.cacheDir, "shared_files")
                dir.mkdirs()
                val file = File(dir, decoded.fileName)
                file.writeBytes(decoded.fileBytes)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, decoded.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("FileBubble", "open failed", e)
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                decoded.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                FilePayload.formatSize(decoded.sizeBytes) + " · tap to open",
                style = MaterialTheme.typography.labelSmall,
                color = subtextColor,
            )
        }
    }
}
