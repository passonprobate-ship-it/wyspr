package com.wyspr.feature.messaging.audio

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
internal fun AudioBubble(
    body: String,
    fromSelf: Boolean,
    cacheKey: Any,
) {
    val decoded = remember(body) { AudioPayload.decode(body) }
    if (decoded == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("Voice note", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(cacheKey) {
        onDispose {
            player?.release()
        }
    }

    val durationText = remember(decoded.durationMs) { formatDuration(decoded.durationMs) }
    val textColor = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val iconTint = if (fromSelf) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.primary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                if (playing) {
                    player?.pause()
                    playing = false
                } else {
                    val mp = player ?: createPlayer(context.cacheDir, decoded, cacheKey)
                    if (mp != null) {
                        player = mp
                        mp.setOnCompletionListener {
                            playing = false
                            mp.seekTo(0)
                        }
                        mp.start()
                        playing = true
                    }
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = iconTint,
            )
        }
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = textColor.copy(alpha = 0.6f),
        )
        Text(
            durationText,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}

private fun createPlayer(
    cacheDir: File,
    decoded: AudioPayload.Decoded,
    cacheKey: Any,
): MediaPlayer? {
    val file = File(cacheDir, "audio_play_$cacheKey.m4a")
    return try {
        file.writeBytes(decoded.audioBytes)
        val mp = MediaPlayer()
        mp.setDataSource(file.absolutePath)
        mp.prepare()
        mp
    } catch (e: Exception) {
        Log.e("AudioBubble", "playback failed", e)
        file.delete()
        null
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(1)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
