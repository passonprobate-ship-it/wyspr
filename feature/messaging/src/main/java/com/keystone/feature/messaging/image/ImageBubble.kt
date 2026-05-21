package com.keystone.feature.messaging.image

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Renders a [keystone:img:...] message body as an inline image
 * bubble. Decodes the base64-JPEG once per (key, body) using
 * `remember` so scroll churn doesn't re-decode every frame.
 *
 * Falls back to a "broken image" icon when decoding fails — same
 * conservative posture as [com.keystone.feature.messaging.location.LocationPayload]:
 * malformed payloads degrade gracefully into a visible placeholder
 * rather than crashing the message list.
 */
@Composable
internal fun ImageBubble(
    body: String,
    cacheKey: Any,
) {
    val bytes = remember(cacheKey) { ImagePayload.decode(body) }
    if (bytes == null) {
        BrokenImagePlaceholder()
        return
    }
    val bitmap = remember(cacheKey) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    if (bitmap == null) {
        BrokenImagePlaceholder()
        return
    }
    // Bounded by max width/height; no fillMaxWidth so the image
    // takes its NATURAL aspect inside those bounds instead of
    // shrinking to fit a wide bubble (which left big empty space
    // around portrait photos in v0.8.1).
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun BrokenImagePlaceholder() {
    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 80.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.BrokenImage,
            contentDescription = "Broken photo",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
        Text(
            "Couldn't load photo",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
