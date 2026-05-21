package com.keystone.feature.messaging.image

import android.util.Base64

/**
 * Tagged-body encoding for "this is a tiny photo, not text" payloads
 * carried inside the existing [com.keystone.feature.messaging.MessageEnvelope.body]
 * + [com.keystone.feature.messaging.groups.GroupMessageEnvelope.body]
 * string field. Same shape as [com.keystone.feature.messaging.location.LocationPayload],
 * different prefix.
 *
 * Wire shape (in the body string):
 *
 *     keystone:img:{base64-of-jpeg-bytes}
 *
 * Base64 uses the standard alphabet without padding so the byte
 * budget stays predictable. Decoders accept padded input too —
 * downstream `Base64.decode` is liberal about it.
 *
 * Body cap on the envelope is 16 KB. The 13-byte prefix + ~4/3
 * base64 inflation means the underlying JPEG must be ≤ 12,275
 * bytes; [ImageCompressor] handles the size-fit loop. Receivers
 * defend against malformed payloads by returning null from
 * [decode] — same convention as [LocationPayload].
 */
object ImagePayload {

    const val PREFIX = "keystone:img:"

    /** Encode raw JPEG bytes into the body-string form. */
    fun encode(jpegBytes: ByteArray): String =
        PREFIX + Base64.encodeToString(jpegBytes, Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Return the raw JPEG bytes if [body] is a well-formed image
     * payload, or null otherwise. Never throws on malformed input —
     * a corrupt body just renders as fallback text.
     */
    fun decode(body: String): ByteArray? {
        if (!body.startsWith(PREFIX)) return null
        val b64 = body.removePrefix(PREFIX)
        return runCatching { Base64.decode(b64, Base64.NO_WRAP or Base64.NO_PADDING) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && looksLikeJpeg(it) }
    }

    fun isImage(body: String): Boolean = body.startsWith(PREFIX)

    /**
     * Cheap header check — first two bytes of every JPEG are
     * 0xFF 0xD8 (SOI marker). Filters obviously-corrupt payloads
     * before we try to decode into a Bitmap.
     */
    private fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
}
