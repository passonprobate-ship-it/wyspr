package com.keystone.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted form of a trust edge. The (from, to) pair is unique — a
 * second edge with the same endpoints replaces the first.
 *
 * Stored as opaque blobs:
 *   - `fromPub` / `toPub` — 32-byte Ed25519 public keys
 *   - `certBlob` — full signed InvitationCertificate (CBOR wire form)
 *   - `certSigner` — 32-byte Ed25519 public key of the cert signer
 *   - `peerOnion` — the not-self endpoint's HSv3 .onion (56 chars,
 *     nullable). Captured from the peer's HandshakeQr at pairing
 *     time; used by Sprint 4's TorHiddenServiceTransport.
 */
@Entity(
    tableName = "trust_edge",
    primaryKeys = ["fromPub", "toPub"],
)
data class TrustEdgeEntity(
    @ColumnInfo(name = "fromPub") val fromPub: ByteArray,
    @ColumnInfo(name = "toPub") val toPub: ByteArray,
    @ColumnInfo(name = "vouchLevel") val vouchLevel: String,
    @ColumnInfo(name = "establishedAt") val establishedAt: Long,
    @ColumnInfo(name = "certBlob") val certBlob: ByteArray,
    @ColumnInfo(name = "certSigner") val certSigner: ByteArray,
    @ColumnInfo(name = "peerOnion") val peerOnion: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustEdgeEntity) return false
        return fromPub.contentEquals(other.fromPub) &&
            toPub.contentEquals(other.toPub) &&
            vouchLevel == other.vouchLevel &&
            establishedAt == other.establishedAt &&
            certBlob.contentEquals(other.certBlob) &&
            certSigner.contentEquals(other.certSigner) &&
            peerOnion == other.peerOnion
    }

    override fun hashCode(): Int {
        var result = fromPub.contentHashCode()
        result = 31 * result + toPub.contentHashCode()
        result = 31 * result + vouchLevel.hashCode()
        result = 31 * result + establishedAt.hashCode()
        result = 31 * result + certBlob.contentHashCode()
        result = 31 * result + certSigner.contentHashCode()
        result = 31 * result + (peerOnion?.hashCode() ?: 0)
        return result
    }
}

@Entity(
    tableName = "revocation",
    primaryKeys = ["issuerPub", "targetPub"],
)
data class RevocationEntity(
    @ColumnInfo(name = "issuerPub") val issuerPub: ByteArray,
    @ColumnInfo(name = "targetPub") val targetPub: ByteArray,
    @ColumnInfo(name = "issuedAt") val issuedAt: Long,
    @ColumnInfo(name = "reasonCode") val reasonCode: String,
    @ColumnInfo(name = "signature") val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RevocationEntity) return false
        return issuerPub.contentEquals(other.issuerPub) &&
            targetPub.contentEquals(other.targetPub) &&
            issuedAt == other.issuedAt &&
            reasonCode == other.reasonCode &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = issuerPub.contentHashCode()
        result = 31 * result + targetPub.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + reasonCode.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
