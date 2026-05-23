package com.wyspr.feature.messaging.file

import android.util.Base64

object FilePayload {

    const val PREFIX = "wyspr:file:"
    private const val B64_FLAGS = Base64.NO_WRAP or Base64.NO_PADDING
    const val MAX_RAW_BYTES = 140_000

    fun encode(fileName: String, mimeType: String, fileBytes: ByteArray): String {
        val b64 = Base64.encodeToString(fileBytes, B64_FLAGS)
        val safeName = fileName.replace(':', '_').replace('\n', '_')
        val safeMime = mimeType.replace(':', '_')
        return "$PREFIX$safeName:$safeMime:${fileBytes.size}:$b64"
    }

    fun decode(body: String): Decoded? {
        if (!body.startsWith(PREFIX)) return null
        val rest = body.substring(PREFIX.length)
        val parts = rest.split(':', limit = 4)
        if (parts.size < 4) return null
        val fileName = parts[0]
        val mimeType = parts[1]
        val sizeBytes = parts[2].toLongOrNull() ?: return null
        val b64 = parts[3]
        val bytes = runCatching { Base64.decode(b64, B64_FLAGS) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        return Decoded(fileName = fileName, mimeType = mimeType, sizeBytes = sizeBytes, fileBytes = bytes)
    }

    fun isFile(body: String): Boolean = body.startsWith(PREFIX)

    data class Decoded(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val fileBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return fileName == other.fileName && mimeType == other.mimeType &&
                sizeBytes == other.sizeBytes && fileBytes.contentEquals(other.fileBytes)
        }
        override fun hashCode(): Int {
            var r = fileName.hashCode()
            r = 31 * r + mimeType.hashCode()
            r = 31 * r + sizeBytes.hashCode()
            r = 31 * r + fileBytes.contentHashCode()
            return r
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
