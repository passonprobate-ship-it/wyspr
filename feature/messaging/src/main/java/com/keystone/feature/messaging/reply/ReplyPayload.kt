package com.keystone.feature.messaging.reply

import android.util.Base64

/**
 * Reply quoting encoded inline in the message body, no protocol bump.
 * Same convention as `keystone:loc:` (location) and `keystone:img:`
 * (image): a tagged prefix on the body that lets receivers detect
 * the variant and degrade gracefully on older clients.
 *
 *     keystone:reply:<base64url(message_id, 16 bytes)>:<plaintext body>
 *
 * Old clients render the marker as part of the message body — visible
 * but readable. New clients strip the prefix, look up the quoted
 * message by id, and render a quoted-snippet pill at the top of the
 * bubble.
 *
 * Wire integrity: the marker is part of `MessageEnvelope.body`, so
 * the sender's Ed25519 signature covers it. A man-in-the-middle that
 * tampers with the quoted id would also break the signature.
 */
object ReplyPayload {

    private const val PREFIX = "keystone:reply:"

    /** Base64URL — no padding, no `+`/`/` chars (URL-safe + body-safe). */
    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /**
     * Wrap [body] with a reply-to reference. The encoded id is the
     * 16-byte `MessageEntity.id` of the message being replied to.
     */
    fun encode(replyToId: ByteArray, body: String): String {
        require(replyToId.size == ID_LENGTH) { "replyToId must be $ID_LENGTH bytes" }
        val encoded = Base64.encodeToString(replyToId, B64_FLAGS)
        return "$PREFIX$encoded:$body"
    }

    /**
     * Decode a reply-wrapped body. Returns null if the body isn't a
     * reply, has a malformed prefix, or carries an unexpected id
     * length. Receivers should fall back to the raw body on null.
     */
    fun decode(body: String): Decoded? {
        if (!body.startsWith(PREFIX)) return null
        // Drop the prefix; the next `:` separates the encoded id from
        // the inner body. The inner body may contain `:` itself (URLs,
        // emoji wrappers), so we split on the first one only.
        val rest = body.substring(PREFIX.length)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val encodedId = rest.substring(0, sep)
        val inner = rest.substring(sep + 1)
        val id = runCatching { Base64.decode(encodedId, B64_FLAGS) }.getOrNull()
            ?: return null
        if (id.size != ID_LENGTH) return null
        return Decoded(replyToId = id, body = inner)
    }

    /** True if [body] carries a reply marker. Cheaper than [decode]. */
    fun isReply(body: String): Boolean = body.startsWith(PREFIX)

    data class Decoded(
        val replyToId: ByteArray,
        val body: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return replyToId.contentEquals(other.replyToId) && body == other.body
        }
        override fun hashCode(): Int = 31 * replyToId.contentHashCode() + body.hashCode()
    }

    const val ID_LENGTH = 16
}
