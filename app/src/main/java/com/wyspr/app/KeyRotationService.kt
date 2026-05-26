package com.wyspr.app

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import android.util.Log
import com.wyspr.app.transport.TorHsKey
import com.wyspr.core.crypto.Cbor
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.entities.KeyRotationEntity
import com.wyspr.core.identity.CommunityId
import com.wyspr.core.identity.PublicKey
import com.wyspr.core.transport.TorBackend
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
    private val torBackend: TorBackend,
) {

    fun isDue(): Boolean = rotationSettings.isDueForRotation()

    suspend fun recoverPendingRotation() {
        val pendingFile = File(context.filesDir, PENDING_ROTATION_FILE)
        if (!pendingFile.exists()) return
        runCatching {
            val pendingData = parsePendingFile(pendingFile.readBytes())
                ?: run { pendingFile.delete(); return }

            // If the seed was saved, ensure it's planted so the DB can be
            // opened with the new key.
            if (pendingData.newSeed != null) {
                try {
                    keystore.plantSeed(pendingData.newSeed)
                } catch (_: Throwable) {
                    // plantSeed may fail if the seed is already planted
                    // (partial recovery after keystore.reset succeeded).
                }
            }

            // Try to open the DB. If rekey happened but plantSeed didn't,
            // the DB key derived from the new seed (saved in the pending
            // file) lets us reopen.
            if (!database.isOpen) {
                try {
                    database.open()
                } catch (_: Throwable) {
                    // DB may be keyed with the new passphrase but identity
                    // key wasn't planted yet — open() derives from keystore
                    // which may still have the old key or be reset. If
                    // plantSeed above succeeded, this should work. If not,
                    // we can't recover.
                    return
                }
            }

            val cert = pendingData.cert
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

            val isFounder = membership.isFounder
            val communityIdBytes = membership.communityId.copyOf()

            // Derive the new DB key BEFORE writing the pending file so we
            // can include it in the recovery payload.
            val newDbKey = hkdfDeriveSubkey(newSeed, DB_KEY_INFO)

            // Write the pending file with cert + seed + DB key BEFORE any
            // destructive operations. If the process dies at any point
            // after this, recoverPendingRotation() can resume.
            val pendingFile = File(context.filesDir, PENDING_ROTATION_FILE)
            val pendingPayload = buildPendingFile(wireBytes, newSeed, newDbKey)
            pendingFile.writeBytes(pendingPayload)

            try {
                database.rekey(newDbKey)
            } finally {
                newDbKey.fill(0)
            }

            keystore.reset()

            keystore.plantSeed(newSeed)
            // Seed has been copied into the keystore; zero it now.
            newSeed.fill(0)

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

            rekeyLocal(oldPub.bytes, newPub.bytes)

            pendingFile.delete()
            rotationSettings.recordRotation()

            Log.d(TAG, "rotate: restarting Tor with new HS key")
            runCatching { torBackend.stop() }
            runCatching { torBackend.start() }

            return true
        } finally {
            // Zero only if not already zeroed above (fill is idempotent).
            newSeed.fill(0)
        }
    }

    private suspend fun rekeyLocal(oldBytes: ByteArray, newBytes: ByteArray) {
        database.runInTransaction {
            val edges = database.trustEdgeDao.all()
            for (edge in edges) {
                val fromMatch = edge.fromPub.contentEquals(oldBytes)
                val toMatch = edge.toPub.contentEquals(oldBytes)
                val signerMatch = edge.certSigner.contentEquals(oldBytes)
                if (!fromMatch && !toMatch && !signerMatch) continue

                database.trustEdgeDao.delete(edge.fromPub, edge.toPub)
                database.trustEdgeDao.upsert(
                    edge.copy(
                        fromPub = if (fromMatch) newBytes else edge.fromPub,
                        toPub = if (toMatch) newBytes else edge.toPub,
                        certSigner = if (signerMatch) newBytes else edge.certSigner,
                    ),
                )
            }

            database.messageDao.rekeyThread(oldBytes, newBytes)
            database.messageDao.rekeyFromPub(oldBytes, newBytes)
            database.messageDao.rekeyToPub(oldBytes, newBytes)
        }
    }

    /**
     * Pending file format: CBOR array [certWireBytes, newSeed, newDbKey].
     * Contains everything needed to resume rotation after a crash.
     */
    private fun buildPendingFile(
        certWireBytes: ByteArray,
        newSeed: ByteArray,
        newDbKey: ByteArray,
    ): ByteArray = Cbor.encode {
        arrayHeader(3)
        bytes(certWireBytes)
        bytes(newSeed)
        bytes(newDbKey)
    }

    private data class PendingData(
        val cert: KeyRotationCertificate,
        val newSeed: ByteArray?,
        val newDbKey: ByteArray?,
    )

    private fun parsePendingFile(blob: ByteArray): PendingData? = runCatching {
        // Try new format first: CBOR array [certBytes, seed, dbKey]
        Cbor.decode(blob) {
            val n = arrayHeader()
            if (n == 3) {
                val certBytes = bytes()
                val seed = bytes()
                val dbKey = bytes()
                val cert = KeyRotationCertificate.fromWire(certBytes)
                PendingData(cert, seed, dbKey)
            } else {
                null
            }
        }
    }.getOrElse {
        // Fall back to legacy format: bare cert wire bytes
        runCatching {
            PendingData(KeyRotationCertificate.fromWire(blob), null, null)
        }.getOrNull()
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
        const val TAG = "KeyRotation"
        const val SEED_BYTES = 32
        const val PENDING_ROTATION_FILE = "pending_rotation.cbor"
        val HKDF_SALT = "WYSPR/v1/HKDF-SALT".encodeToByteArray()
        val TOR_HS_INFO = "WYSPR/v1/tor-hs".encodeToByteArray()
        val DB_KEY_INFO = "WYSPR/v1/db".encodeToByteArray()
    }
}
