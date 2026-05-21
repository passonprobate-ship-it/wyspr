package com.keystone.feature.messaging.groups

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.keystone.core.crypto.Cbor
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.identity.GroupId
import com.keystone.core.identity.PublicKey

/**
 * Signed assertion that [memberPub] is a member of [groupId]. Mirrors
 * `InvitationCertificate` in shape and signing discipline — see
 * `docs/GROUPS.md` and `docs/SECURITY-MODEL.md` §3.
 *
 * v1: only the group's creator can issue a `GroupMembership`. The
 * cert chain has exactly one link: creator → member. v2+ will allow
 * delegated issuance and require a chain back to the creator.
 *
 * The [name] and [createdAt] of the group are denormalised into the
 * cert so a member who only ever sees this cert (no prior group
 * gossip) can recompute the expected `groupId` and verify it matches
 * the cert's declared [groupId]. This is the only place we accept
 * the issuer's word on group identity — once verified, subsequent
 * messages reference [groupId] alone.
 */
data class GroupMembership(
    val version: Int,
    val groupId: GroupId,
    val memberPub: PublicKey,
    val name: String,
    val createdAt: Long,
    val issuerPub: PublicKey,
    val issuedAt: Long,
    val signature: ByteArray,
) {
    init {
        require(version == VERSION) { "unsupported group-membership version: $version" }
        require(signature.size == SIG_LENGTH) { "ed25519 signature must be $SIG_LENGTH bytes" }
    }

    fun signedBytes(): ByteArray = encodeSignedFields(
        version = version,
        groupId = groupId,
        memberPub = memberPub,
        name = name,
        createdAt = createdAt,
        issuerPub = issuerPub,
        issuedAt = issuedAt,
    )

    fun wireBytes(): ByteArray = Cbor.encode {
        arrayHeader(8)
        uint(version.toLong())
        bytes(groupId.bytes)
        bytes(memberPub.bytes)
        bytes(name.encodeToByteArray())
        uint(createdAt)
        bytes(issuerPub.bytes)
        uint(issuedAt)
        bytes(signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMembership) return false
        return version == other.version &&
            groupId.bytes.contentEquals(other.groupId.bytes) &&
            memberPub.bytes.contentEquals(other.memberPub.bytes) &&
            name == other.name &&
            createdAt == other.createdAt &&
            issuerPub.bytes.contentEquals(other.issuerPub.bytes) &&
            issuedAt == other.issuedAt &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + groupId.bytes.contentHashCode()
        r = 31 * r + memberPub.bytes.contentHashCode()
        r = 31 * r + name.hashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + issuerPub.bytes.contentHashCode()
        r = 31 * r + issuedAt.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    companion object {
        const val VERSION = 1
        const val SIG_LENGTH = 64

        /**
         * Build and sign a fresh membership cert. Caller is responsible
         * for ensuring [issuerPub] matches the keystore's identity key
         * (v1 = the group creator).
         */
        fun issue(
            keystore: KeystoreManager,
            groupId: GroupId,
            memberPub: PublicKey,
            name: String,
            createdAt: Long,
            issuerPub: PublicKey,
            issuedAt: Long,
        ): GroupMembership {
            val signed = encodeSignedFields(
                version = VERSION,
                groupId = groupId,
                memberPub = memberPub,
                name = name,
                createdAt = createdAt,
                issuerPub = issuerPub,
                issuedAt = issuedAt,
            )
            val signature = keystore.sign(signed)
            return GroupMembership(
                version = VERSION,
                groupId = groupId,
                memberPub = memberPub,
                name = name,
                createdAt = createdAt,
                issuerPub = issuerPub,
                issuedAt = issuedAt,
                signature = signature,
            )
        }

        fun fromWire(blob: ByteArray): GroupMembership = Cbor.decode(blob) {
            val n = arrayHeader()
            require(n == 8) { "GroupMembership must have 8 fields, got $n" }
            val version = uint().toInt()
            val groupId = bytes()
            val memberPub = bytes()
            val name = bytes().decodeToString()
            val createdAt = uint()
            val issuerPub = bytes()
            val issuedAt = uint()
            val signature = bytes()
            GroupMembership(
                version = version,
                groupId = GroupId(groupId),
                memberPub = PublicKey(memberPub),
                name = name,
                createdAt = createdAt,
                issuerPub = PublicKey(issuerPub),
                issuedAt = issuedAt,
                signature = signature,
            )
        }

        private fun encodeSignedFields(
            version: Int,
            groupId: GroupId,
            memberPub: PublicKey,
            name: String,
            createdAt: Long,
            issuerPub: PublicKey,
            issuedAt: Long,
        ): ByteArray = Cbor.encode {
            arrayHeader(7)
            uint(version.toLong())
            bytes(groupId.bytes)
            bytes(memberPub.bytes)
            bytes(name.encodeToByteArray())
            uint(createdAt)
            bytes(issuerPub.bytes)
            uint(issuedAt)
        }
    }
}

/**
 * Verifies a [GroupMembership] cert's Ed25519 signature against
 * [GroupMembership.issuerPub] and checks that the declared [GroupMembership.groupId]
 * actually equals `GroupId.derive(issuerPub, name, createdAt)` — i.e.
 * the cert can't claim a `groupId` inconsistent with its own fields.
 *
 * v1 callers MUST additionally verify that the cert's `issuerPub`
 * equals the group's known `creatorPub` (the group record's
 * `creatorPub` column). v2+ will follow a cert chain back to the
 * creator instead.
 *
 * Returns true only if every check passes. Does not enforce time
 * windows — groups don't expire in v1.
 */
fun GroupMembership.verify(sodium: LazySodiumAndroid): Boolean {
    if (signature.size != Sign.BYTES) return false
    val derived = GroupId.derive(issuerPub, name, createdAt)
    if (!derived.bytes.contentEquals(groupId.bytes)) return false
    val signed = signedBytes()
    return sodium.cryptoSignVerifyDetached(signature, signed, signed.size, issuerPub.bytes)
}
