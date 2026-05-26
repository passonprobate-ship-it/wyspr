package com.wyspr.feature.coordination

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.crypto.KeystoreManager

data class EventEnvelope(
    val version: Int,
    val id: ByteArray,
    val creatorPub: ByteArray,
    val communityId: ByteArray,
    val title: ByteArray,
    val description: ByteArray?,
    val location: ByteArray?,
    val startsAt: Long,
    val endsAt: Long?,
    val createdAt: Long,
    val status: Int,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported event version: $version" }
        require(id.size == ID_LENGTH) { "event id must be $ID_LENGTH bytes" }
        require(creatorPub.size == PUB_LENGTH) { "creatorPub must be $PUB_LENGTH bytes" }
        require(communityId.size == PUB_LENGTH) { "communityId must be $PUB_LENGTH bytes" }
        require(signature.size == SIG_LENGTH) { "signature must be $SIG_LENGTH bytes" }
        require(title.size <= MAX_TITLE_BYTES) { "title exceeds $MAX_TITLE_BYTES bytes" }
        if (description != null) {
            require(description.size <= MAX_DESC_BYTES) { "description exceeds $MAX_DESC_BYTES bytes" }
        }
        require(status in 0..1) { "status must be 0 (active) or 1 (cancelled)" }
    }

    val titleString: String get() = title.decodeToString()
    val descriptionString: String? get() = description?.decodeToString()
    val locationString: String? get() = location?.decodeToString()

    fun signedBytes(): ByteArray = encodeSignedFields(
        version, id, creatorPub, communityId, title, description, location,
        startsAt, endsAt, createdAt, status,
    )

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(12)
        uint(version.toLong())
        bytes(id)
        bytes(creatorPub)
        bytes(communityId)
        bytes(title)
        if (description != null) bytes(description) else nullValue()
        if (location != null) bytes(location) else nullValue()
        uint(startsAt)
        if (endsAt != null) uint(endsAt) else nullValue()
        uint(createdAt)
        uint(status.toLong())
        bytes(signature)
    }

    companion object {
        const val VERSION = 1
        const val ID_LENGTH = 16
        const val PUB_LENGTH = 32
        const val SIG_LENGTH = 64
        const val MAX_TITLE_BYTES = 256
        const val MAX_DESC_BYTES = 4096

        fun issue(
            keystore: KeystoreManager,
            id: ByteArray,
            creatorPub: ByteArray,
            communityId: ByteArray,
            title: String,
            description: String?,
            location: String?,
            startsAt: Long,
            endsAt: Long?,
            now: Long = System.currentTimeMillis() / 1000,
            status: Int = 0,
        ): EventEnvelope {
            val titleBytes = title.encodeToByteArray()
            val descBytes = description?.encodeToByteArray()
            val locBytes = location?.encodeToByteArray()
            val signedBytes = encodeSignedFields(
                VERSION, id, creatorPub, communityId, titleBytes, descBytes, locBytes,
                startsAt, endsAt, now, status,
            )
            val signature = keystore.sign(signedBytes)
            return EventEnvelope(
                version = VERSION, id = id, creatorPub = creatorPub,
                communityId = communityId, title = titleBytes,
                description = descBytes, location = locBytes,
                startsAt = startsAt, endsAt = endsAt, createdAt = now,
                status = status, signature = signature,
            )
        }

        fun fromWire(blob: ByteArray): EventEnvelope = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 12) { "EventEnvelope must have 12 fields, got $n" }
            val version = uint().toIntChecked()
            val id = bytes()
            val creatorPub = bytes()
            val communityId = bytes()
            val title = bytes()
            val description = bytesOrNull()
            val location = bytesOrNull()
            val startsAt = uint()
            val endsAt = uintOrNull()
            val createdAt = uint()
            val status = uint().toIntChecked()
            val signature = bytes()
            EventEnvelope(
                version, id, creatorPub, communityId, title, description, location,
                startsAt, endsAt, createdAt, status, signature,
            )
        }

        private fun encodeSignedFields(
            version: Int, id: ByteArray, creatorPub: ByteArray,
            communityId: ByteArray, title: ByteArray, description: ByteArray?,
            location: ByteArray?, startsAt: Long, endsAt: Long?,
            createdAt: Long, status: Int,
        ): ByteArray = Cbor.encode {
            arrayHeader(11)
            uint(version.toLong())
            bytes(id)
            bytes(creatorPub)
            bytes(communityId)
            bytes(title)
            if (description != null) bytes(description) else nullValue()
            if (location != null) bytes(location) else nullValue()
            uint(startsAt)
            if (endsAt != null) uint(endsAt) else nullValue()
            uint(createdAt)
            uint(status.toLong())
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}

fun EventEnvelope.verify(
    sodium: LazySodiumAndroid,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
    maxFutureSkewSeconds: Long = 3600,
): Boolean {
    if (signature.size != Sign.BYTES) return false
    if (createdAt < 0) return false
    if (createdAt > nowSeconds + maxFutureSkewSeconds) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, creatorPub)
}
