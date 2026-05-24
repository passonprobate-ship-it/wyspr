package com.wyspr.app

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.wyspr.app.transport.TorHsKey
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.KeyRotationEntity
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.trust.KeyRotationCertificate
import com.wyspr.core.ui.settings.KeyRotationSettings
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class KeyRotationService(
    private val context: Context,
    private val keystore: KeystoreManager,
    private val database: WysprDatabase,
    private val sodium: LazySodiumAndroid,
    private val rotationSettings: KeyRotationSettings,
) {

    fun isDue(): Boolean = rotationSettings.isDueForRotation()

    suspend fun recoverPendingRotation() {
        val pendingFile = File(context.filesDir, PENDING_ROTATION_FILE)
        if (!pendingFile.exists()) return
        runCatching {
            if (!database.isOpen) database.open()
            val wireBytes = pendingFile.readBytes()
            val cert = KeyRotationCertificate.fromWire(wireBytes)
            val onionString = cert.newOnion?.let { String(it, Charsets.US_ASCII) }
            database.keyRotationDao.upsert(
                KeyRotationEntity(
                    oldPub = cert.oldPub.bytes,
                    newPub = cert.newPub.bytes,
                    communityId = cert.communityId.bytes,
                    issuedAt = cert.issuedAt,
                    newOnion = onionString,
                    signature = cert.signature,
                ),
            )
            pendingFile.delete()
            rotationSettings.recordRotation()
        }
    }

    suspend fun rotate(): Boolean {
        val oldPub = runCatching {
            PublicKey(keystore.loadOrCreateIdentityKey().publicKey)
        }.getOrNull() ?: return false

        if (!database.isOpen) database.open()
        val membership = database.communityMembershipDao.firstOrNull()
            ?: return false
        val communityId = CommunityId(membership.communityId)

        val newSeed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val newPubBytes = ByteArray(Sign.PUBLICKEYBYTES)
            val newSecBytes = ByteArray(Sign.SECRETKEYBYTES)
            require(sodium.cryptoSignSeedKeypair(newPubBytes, newSecBytes, newSeed))
            newSecBytes.fill(0)
            val newPub = PublicKey(newPubBytes)

            val hsSeed = hkdfDeriveSubkey(newSeed, TOR_HS_INFO)
            val newOnion = try {
                TorHsKey.derive(hsSeed).onionAddress
                    .toByteArray(Charsets.US_ASCII)
            } finally {
                hsSeed.fill(0)
            }

            val cert = KeyRotationCertificate.issue(
                keystore = keystore,
                oldPub = oldPub,
                newPub = newPub,
                communityId = communityId,
                newOnion = newOnion,
            )
            val wireBytes = cert.wireBytes()

            val pendingFile = File(context.filesDir, PENDING_ROTATION_FILE)
            pendingFile.writeBytes(wireBytes)

            val isFounder = membership.isFounder
            val communityIdBytes = membership.communityId.copyOf()

            val newDbKey = hkdfDeriveSubkey(newSeed, DB_KEY_INFO)
            try {
                database.rekey(newDbKey)
            } finally {
                newDbKey.fill(0)
            }

            keystore.reset()

            keystore.plantSeed(newSeed)

            database.open()

            database.keyRotationDao.upsert(
                KeyRotationEntity(
                    oldPub = cert.oldPub.bytes,
                    newPub = cert.newPub.bytes,
                    communityId = cert.communityId.bytes,
                    issuedAt = cert.issuedAt,
                    newOnion = cert.newOnion?.let { String(it, Charsets.US_ASCII) },
                    signature = cert.signature,
                ),
            )

            database.communityMembershipDao.upsert(
                com.wyspr.core.database.entities.CommunityMembershipEntity(
                    communityId = communityIdBytes,
                    isFounder = isFounder,
                    foundedAt = System.currentTimeMillis() / 1000,
                    displayName = null,
                ),
            )

            pendingFile.delete()
            rotationSettings.recordRotation()
            return true
        } finally {
            newSeed.fill(0)
        }
    }

    private fun hkdfDeriveSubkey(seed: ByteArray, info: ByteArray): ByteArray {
        val prk = hkdfExtract(HKDF_SALT, seed)
        try {
            return hkdfExpand(prk, info, 32)
        } finally {
            prk.fill(0)
        }
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val take = minOf(hashLen, length - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
        }
        t.fill(0)
        return out
    }

    private companion object {
        const val SEED_BYTES = 32
        const val PENDING_ROTATION_FILE = "pending_rotation.cbor"
        val HKDF_SALT = "WYSPR/v1/HKDF-SALT".encodeToByteArray()
        val TOR_HS_INFO = "WYSPR/v1/tor-hs".encodeToByteArray()
        val DB_KEY_INFO = "WYSPR/v1/db".encodeToByteArray()
    }
}
