package com.wyspr.feature.messaging.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a [wyspr:img:...] message body as an inline image
 * bubble. Decodes the base64-JPEG once per (key, body) using
 * `remember` so scroll churn doesn't re-decode every frame.
 *
 * Falls back to a "broken image" icon when decoding fails — same
 * conservative posture as [com.wyspr.feature.messaging.location.LocationPayload]:
 * malformed payloads degrade gracefully into a visible placeholder
 * rather than crashing the message list.
 */
// Shared bitmap cache. Bounded by total decoded pixel weight (4 bytes
// per ARGB_8888 pixel). Conservatively 16MB — about 1MP of decoded
// imagery, enough for a few visible bubbles and a small backscroll.
// Scrolling away and back doesn't re-decode unless the cache evicts.
private val bitmapCache: LruCache<Any, Bitmap> =
    object : LruCache<Any, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Any, value: Bitmap): Int = value.byteCount
    }

@Composable
internal fun ImageBubble(
    body: String,
    cacheKey: Any,
) {
    // Decode (both base64 + bitmap) is now off the composition thread.
    // produceState emits null until the worker finishes; before this,
    // BitmapFactory.decodeByteArray ran inside `remember` on the
    // composition thread, blocking the first frame for the duration of
    // the decode (~5-15ms per WebP on a Galaxy A02s).
    val bitmap by produceState<Bitmap?>(initialValue = bitmapCache.get(cacheKey), key1 = cacheKey) {
        // Already in cache — produceState already emitted, nothing to do.
        if (value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            val bytes = ImagePayload.decode(body) ?: return@withContext null
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (decoded != null) bitmapCache.put(cacheKey, decoded)
            decoded
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        // Still decoding OR decode failed — show placeholder. When the
        // decode finishes the recompose flips us into the Image branch.
        // (A persistent "decoding failed" indicator would need a
        // sentinel; the current behaviour matches the pre-change UX
        // where a failed decode also showed the placeholder.)
        BrokenImagePlaceholder()
        return
    }
    // Bounded by max width/height; no fillMaxWidth so the image
    // takes its NATURAL aspect inside those bounds instead of
    // shrinking to fit a wide bubble (which left big empty space
    // around portrait photos in v0.8.1).
    Image(
        bitmap = bmp.asImageBitmap(),
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
