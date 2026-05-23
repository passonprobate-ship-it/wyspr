package com.wyspr.feature.messaging.audio

import android.util.Base64

object AudioPayload {

    const val PREFIX = "wyspr:audio:"
    private const val B64_FLAGS = Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(audioBytes: ByteArray, durationMs: Long): String {
        val b64 = Base64.encodeToString(audioBytes, B64_FLAGS)
        return "$PREFIX$durationMs:$b64"
    }

    fun decode(body: String): Decoded? {
        if (!body.startsWith(PREFIX)) return null
        val rest = body.substring(PREFIX.length)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val durationMs = rest.substring(0, sep).toLongOrNull() ?: return null
        val b64 = rest.substring(sep + 1)
        val bytes = runCatching { Base64.decode(b64, B64_FLAGS) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        return Decoded(durationMs = durationMs, audioBytes = bytes)
    }

    fun isAudio(body: String): Boolean = body.startsWith(PREFIX)

    data class Decoded(
        val durationMs: Long,
        val audioBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return durationMs == other.durationMs && audioBytes.contentEquals(other.audioBytes)
        }
        override fun hashCode(): Int = 31 * durationMs.hashCode() + audioBytes.contentHashCode()
    }
}
