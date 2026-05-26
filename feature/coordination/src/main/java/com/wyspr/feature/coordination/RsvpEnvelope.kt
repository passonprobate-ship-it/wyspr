package com.wyspr.feature.coordination

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.crypto.KeystoreManager

data class RsvpEnvelope(
    val version: Int,
    val eventId: ByteArray,
    val responderPub: ByteArray,
    val communityId: ByteArray,
    val status: Int,
    val createdAt: Long,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported RSVP version: $version" }
        require(eventId.size == EVENT_ID_LENGTH) { "eventId must be $EVENT_ID_LENGTH bytes" }
        require(responderPub.size == PUB_LENGTH) { "responderPub must be $PUB_LENGTH bytes" }
        require(communityId.size == PUB_LENGTH) { "communityId must be $PUB_LENGTH bytes" }
        require(signature.size == SIG_LENGTH) { "signature must be $SIG_LENGTH bytes" }
        require(status in 0..2) { "status must be 0 (going), 1 (maybe), or 2 (declined)" }
    }

    fun signedBytes(): ByteArray = encodeSignedFields(
        version, eventId, responderPub, communityId, status, createdAt,
    )

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(7)
        uint(version.toLong())
        bytes(eventId)
        bytes(responderPub)
        bytes(communityId)
        uint(status.toLong())
        uint(createdAt)
        bytes(signature)
    }

    companion object {
        const val VERSION = 1
        const val EVENT_ID_LENGTH = 16
        const val PUB_LENGTH = 32
        const val SIG_LENGTH = 64

        fun issue(
            keystore: KeystoreManager,
            eventId: ByteArray,
            responderPub: ByteArray,
            communityId: ByteArray,
            status: Int,
            now: Long = System.currentTimeMillis() / 1000,
        ): RsvpEnvelope {
            val signedBytes = encodeSignedFields(
                VERSION, eventId, responderPub, communityId, status, now,
            )
            val signature = keystore.sign(signedBytes)
            return RsvpEnvelope(
                version = VERSION, eventId = eventId, responderPub = responderPub,
                communityId = communityId, status = status, createdAt = now,
                signature = signature,
            )
        }

        fun fromWire(blob: ByteArray): RsvpEnvelope = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 7) { "RsvpEnvelope must have 7 fields, got $n" }
            val version = uint().toIntChecked()
            val eventId = bytes()
            val responderPub = bytes()
            val communityId = bytes()
            val status = uint().toIntChecked()
            val createdAt = uint()
            val signature = bytes()
            RsvpEnvelope(version, eventId, responderPub, communityId, status, createdAt, signature)
        }

        private fun encodeSignedFields(
            version: Int, eventId: ByteArray, responderPub: ByteArray,
            communityId: ByteArray, status: Int, createdAt: Long,
        ): ByteArray = Cbor.encode {
            arrayHeader(6)
            uint(version.toLong())
            bytes(eventId)
            bytes(responderPub)
            bytes(communityId)
            uint(status.toLong())
            uint(createdAt)
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

fun RsvpEnvelope.verify(
    sodium: LazySodiumAndroid,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    maxFutureSkewSeconds: Long = 3600,
): Boolean {
    if (signature.size != Sign.BYTES) return false
    if (createdAt < 0) return false
    if (createdAt > nowSeconds + maxFutureSkewSeconds) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, responderPub)
}
