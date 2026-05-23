package com.wyspr.feature.messaging.reactions

import android.util.Base64

object ReactionPayload {

    private const val PREFIX = "wyspr:react:"
    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    const val ID_LENGTH = 16

    fun encode(targetMsgId: ByteArray, emoji: String): String {
        require(targetMsgId.size == ID_LENGTH) { "targetMsgId must be $ID_LENGTH bytes" }
        require(emoji.isNotEmpty()) { "emoji must not be empty" }
        val encoded = Base64.encodeToString(targetMsgId, B64_FLAGS)
        return "$PREFIX$encoded:$emoji"
    }

    fun encodeRetract(targetMsgId: ByteArray): String {
        require(targetMsgId.size == ID_LENGTH) { "targetMsgId must be $ID_LENGTH bytes" }
        val encoded = Base64.encodeToString(targetMsgId, B64_FLAGS)
        return "$PREFIX$encoded:"
    }

    fun decode(body: String): Decoded? {
        if (!body.startsWith(PREFIX)) return null
        val rest = body.substring(PREFIX.length)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val encodedId = rest.substring(0, sep)
        val emoji = rest.substring(sep + 1)
        val id = runCatching { Base64.decode(encodedId, B64_FLAGS) }.getOrNull()
            ?: return null
        if (id.size != ID_LENGTH) return null
        return Decoded(targetMsgId = id, emoji = emoji)
    }

    fun isReaction(body: String): Boolean = body.startsWith(PREFIX)

    data class Decoded(
        val targetMsgId: ByteArray,
        val emoji: String,
    ) {
        val isRetract: Boolean get() = emoji.isEmpty()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return targetMsgId.contentEquals(other.targetMsgId) && emoji == other.emoji
        }
        override fun hashCode(): Int = 31 * targetMsgId.contentHashCode() + emoji.hashCode()
    }
}
