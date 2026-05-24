package com.wyspr.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Static options the keystore reads at construction. The app layer
 * gathers these from user preferences + device capabilities once per
 * process launch; we treat them as immutable here.
 *
 * Why a snapshot and not live settings: the wrapping key is created on
 * first launch and never re-keyed in place. Once the key exists its
 * auth requirements are fixed. A user who toggles their preference
 * after the key exists needs to reset their identity to apply.
 */
data class KeystoreOptions(
    val bindWrappingKeyToBiometric: Boolean,
) {
    companion object {
        val Unbound = KeystoreOptions(bindWrappingKeyToBiometric = false)
    }
}

/**
 * Hardware-backed key management.
 *
 * Wyspr refuses to run on devices without at least a TEE-backed keystore.
 * StrongBox is preferred; TEE is the floor. Software-only key storage is
 * never an acceptable fallback — see SECURITY-MODEL.md §6.
 */
interface KeystoreManager {

    /** Strongest available backing on this device. */
    val backing: Backing

    /**
     * Returns the long-term identity key reference (Ed25519). Generates it
     * on first call and stores it in the hardware keystore. The private
     * key material never leaves the keystore; this returns an opaque
     * handle.
     */
    fun loadOrCreateIdentityKey(): IdentityKeyHandle

    /**
     * Derives a 32-byte symmetric secret bound to the identity key. Used
     * to unlock SQLCipher. Caller is responsible for zeroing the returned
     * array after use.
     */
    fun deriveSubkey(info: ByteArray, length: Int = 32): ByteArray

    /** Signs [message] with the identity Ed25519 private key. */
    fun sign(message: ByteArray): ByteArray

    /**
     * Returns the (X25519 public, X25519 secret) keypair derived from the
     * same seed that backs [loadOrCreateIdentityKey]. Used as the Noise XX
     * static keypair — the static and signing keys share the same
     * hardware-protected origin. SECURITY-MODEL.md §2.3.
     *
     * The returned secret MUST be zeroed by the caller after use.
     */
    fun deriveStaticX25519(): Pair<ByteArray, ByteArray>


    /**
     * True iff the wrapping key is biometric-bound and an op against
     * it would currently throw [KeystoreAuthRequired]. Test/dev impls
     * default to "no auth ever needed."
     */
    fun needsAuth(): Boolean = false

    /** Whether the wrapping key requires user authentication at all. */
    val isWrappingKeyBound: Boolean get() = false

    fun reset()

    /**
     * Write a pre-generated seed so the next [loadOrCreateIdentityKey]
     * call uses it instead of generating a random one. Used by key
     * rotation: the new seed (and therefore the new pubkey) must be
     * known BEFORE the old key is destroyed so the rotation cert can
     * reference the new pubkey.
     */
    fun plantSeed(seed: ByteArray)

    enum class Backing { STRONGBOX, TEE, SOFTWARE_REJECTED }

    /** Opaque handle to a keystore-resident private key. */
    interface IdentityKeyHandle {
        /** Ed25519 public key bytes (32 bytes). */
        val publicKey: ByteArray
        /** Stable installation-scoped key alias inside the keystore. */
        val alias: String
    }
}

/**
 * Hardware-backed Ed25519 via libsodium with the seed wrapped by an
 * AndroidKeyStore AES-256-GCM key. The wrapping key is hardware-backed
 * (StrongBox preferred, TEE required); the wrapped seed lives in the
 * app's private files dir. The seed itself never touches disk in
 * plaintext, and the wrapping key cannot be exported.
 *
 * Why not AndroidKeyStore Ed25519 directly: it's only exposed via the
 * AndroidKeyStore provider from API 33+, and Wyspr supports API 26+.
 * Wrapping the libsodium seed with a hardware AES key gives equivalent
 * "private bits never leave hardware-protected storage" guarantees on
 * every supported API level.
 */
class AndroidKeystoreManager(
    private val context: Context,
    private val sodium: LazySodiumAndroid,
    private val optionsProvider: () -> KeystoreOptions = { KeystoreOptions.Unbound },
) : KeystoreManager {

    /**
     * Convenience constructor for tests and call sites that don't need
     * dynamic options. Snapshots the options once.
     */
    constructor(
        context: Context,
        sodium: LazySodiumAndroid,
        options: KeystoreOptions,
    ) : this(context, sodium, optionsProvider = { options })

    private val options: KeystoreOptions get() = optionsProvider()

    private val seedFile: File get() = File(context.filesDir, SEED_FILENAME)

    @Volatile
    private var cachedBacking: KeystoreManager.Backing? = null

    @Volatile
    private var cachedHandle: WysprIdentityHandle? = null

    /**
     * True if the wrapping key currently in the keystore requires the
     * user to have authenticated within the validity window. Reads
     * KeyInfo; returns false if the key doesn't exist yet.
     */
    override val isWrappingKeyBound: Boolean
        get() {
            return try {
                val ks = keystoreInstance()
                if (!ks.containsAlias(WRAPPING_ALIAS)) {
                    false
                } else {
                    val entry = ks.getEntry(WRAPPING_ALIAS, null) as KeyStore.SecretKeyEntry
                    val factory = javax.crypto.SecretKeyFactory.getInstance(
                        entry.secretKey.algorithm,
                        PROVIDER_ANDROID_KEYSTORE,
                    )
                    val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java) as KeyInfo
                    info.isUserAuthenticationRequired
                }
            } catch (_: Throwable) {
                false
            }
        }

    /**
     * Probe whether a sensitive op against the wrapping key would
     * succeed *right now* without a fresh biometric prompt. The
     * implementation tries [Cipher.init] with a throwaway IV — that's
     * the canonical way to test the keystore's current auth window
     * (there's no `KeyInfo.isAuthenticatedNow` API).
     *
     * Returns true when:
     *   - the wrapping key isn't biometric-bound (no auth ever needed), or
     *   - it is bound and the user has authenticated within the validity
     *     window.
     *
     * Returns false when the OS would throw [UserNotAuthenticatedException].
     */
    fun isAuthSatisfied(): Boolean {
        if (!isWrappingKeyBound) return true
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, ByteArray(12)))
            true
        } catch (_: UserNotAuthenticatedException) {
            false
        } catch (_: Throwable) {
            // Any other init failure (wrong IV size etc.) is unrelated to
            // auth; we say "auth is satisfied" so the caller proceeds to
            // the real op and gets a real error there.
            true
        }
    }

    /**
     * Single-call answer to "should the caller show a biometric prompt
     * before the next sensitive operation?"
     *
     *   - If a wrapping key already exists: yes iff it's bound and the
     *     auth window has expired.
     *   - If no key exists yet: yes iff we're about to create a bound
     *     one (encrypt of the new seed needs auth).
     */
    override fun needsAuth(): Boolean {
        val ks = keystoreInstance()
        return if (ks.containsAlias(WRAPPING_ALIAS)) {
            isWrappingKeyBound && !isAuthSatisfied()
        } else {
            options.bindWrappingKeyToBiometric
        }
    }

    /**
     * Destroy the local identity. After this returns:
     *
     *   - The AndroidKeyStore alias for the wrapping key is gone.
     *   - The wrapped-seed file on disk is gone.
     *   - In-memory caches (handle, backing) are cleared.
     *
     * The next call to [loadOrCreateIdentityKey] generates a fresh
     * identity using the *current* options (read lazily via the
     * provider) — so a user who flipped the "bind to biometric" toggle
     * between reset and re-onboarding gets the new behaviour.
     *
     * The SQLCipher database key is derived from the seed, so any DB
     * file on disk becomes unreadable after this — the caller is
     * responsible for deleting it separately (see
     * WysprDatabase.wipe()).
     */
    override fun reset() {
        try {
            val ks = keystoreInstance()
            if (ks.containsAlias(WRAPPING_ALIAS)) ks.deleteEntry(WRAPPING_ALIAS)
        } catch (_: Throwable) {
            // Best-effort — even on failure we still clear local state below.
        }
        try {
            seedFile.delete()
        } catch (_: Throwable) {
            // Same — orphan file is annoying but not load-bearing.
        }
        cachedHandle = null
        cachedBacking = null
    }

    override fun plantSeed(seed: ByteArray) {
        require(seed.size == SEED_BYTES) { "seed must be $SEED_BYTES bytes" }
        ensureWrappingKey()
        val blob = wrapSeed(seed)
        val file = seedFile
        file.parentFile?.mkdirs()
        file.writeBytes(blob)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        cachedHandle = null
    }

    override val backing: KeystoreManager.Backing
        get() = cachedBacking ?: detectBacking().also { cachedBacking = it }

    override fun loadOrCreateIdentityKey(): WysprIdentityHandle {
        cachedHandle?.let { return it }
        check(backing != KeystoreManager.Backing.SOFTWARE_REJECTED) {
            "Wyspr requires hardware-backed key storage. Refusing to run."
        }
        ensureWrappingKey()
        val seed = loadOrCreateSeed()
        try {
            val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
            val secretKey = ByteArray(Sign.SECRETKEYBYTES)
            require(sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)) {
                "libsodium cryptoSignSeedKeypair failed"
            }
            // Zero the secret material we just derived from the seed — sign()
            // re-derives on demand. Public key is safe to retain.
            secretKey.fill(0)
            val handle = WysprIdentityHandle(publicKey = publicKey, alias = IDENTITY_ALIAS)
            cachedHandle = handle
            return handle
        } finally {
            seed.fill(0)
        }
    }

    override fun deriveSubkey(info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "subkey length out of HKDF-SHA256 range" }
        val seed = loadOrCreateSeed()
        try {
            // HKDF-SHA256 with the identity seed as IKM, fixed salt, caller info.
            val prk = hkdfExtract(salt = HKDF_SALT, ikm = seed)
            try {
                return hkdfExpand(prk = prk, info = info, length = length)
            } finally {
                prk.fill(0)
            }
        } finally {
            seed.fill(0)
        }
    }

    override fun sign(message: ByteArray): ByteArray {
        val seed = loadOrCreateSeed()
        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)
        try {
            require(sodium.cryptoSignSeedKeypair(publicKey, secretKey, seed)) {
                "libsodium cryptoSignSeedKeypair failed"
            }
            // Sign the exact bytes the verifier will check. Earlier
            // revisions pre-padded `message` to a 32-byte boundary as a
            // putative timing-side-channel defence, but every verifier
            // (`cryptoSignVerifyDetached` over `signedBytes()`) checks
            // the unpadded form — so the padding silently invalidated
            // every signature. The padding was the wrong defence anyway:
            // for fixed-size payloads (certs) it's pointless, and for
            // variable-size payloads the length is observable on the
            // wire regardless. Ed25519 itself is constant-time wrt the
            // key, and message-length timing leakage at the SHA-512
            // step is dwarfed by network jitter.
            val signature = ByteArray(Sign.BYTES)
            require(
                sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)
            ) { "libsodium cryptoSignDetached failed" }
            return signature
        } finally {
            seed.fill(0)
            secretKey.fill(0)
        }
    }

    /**
     * Derive the X25519 keypair from the same seed used for Ed25519
     * signing. Libsodium provides the standard Ed25519 -> X25519
     * conversion; both keys remain rooted in hardware-keystore material.
     */
    override fun deriveStaticX25519(): Pair<ByteArray, ByteArray> {
        val seed = loadOrCreateSeed()
        val edPub = ByteArray(Sign.PUBLICKEYBYTES)
        val edSec = ByteArray(Sign.SECRETKEYBYTES)
        try {
            require(sodium.cryptoSignSeedKeypair(edPub, edSec, seed)) {
                "libsodium cryptoSignSeedKeypair failed"
            }
            val xPub = ByteArray(X25519_KEY_BYTES)
            val xSec = ByteArray(X25519_KEY_BYTES)
            require(sodium.convertPublicKeyEd25519ToCurve25519(xPub, edPub)) {
                "Ed25519 -> X25519 public conversion failed"
            }
            require(sodium.convertSecretKeyEd25519ToCurve25519(xSec, edSec)) {
                "Ed25519 -> X25519 secret conversion failed"
            }
            return xPub to xSec
        } finally {
            seed.fill(0)
            edSec.fill(0)
        }
    }

    // --------------------------------------------------------------------
    // Wrapping key + seed lifecycle
    // --------------------------------------------------------------------

    private fun ensureWrappingKey() {
        val ks = keystoreInstance()
        if (ks.containsAlias(WRAPPING_ALIAS)) return
        // Try StrongBox first when the device advertises it. Some devices
        // expose the feature flag but still fail at generateKey time
        // (e.g. backend not provisioned on Pixel imports). Fall back to a
        // plain TEE-backed key if StrongBox creation throws.
        if (preferStrongBox()) {
            try {
                generateWrappingKey(strongBox = true, bound = options.bindWrappingKeyToBiometric)
                return
            } catch (_: StrongBoxUnavailableException) {
                // Fall through to TEE-only retry below.
            } catch (_: java.security.ProviderException) {
                // Some OEMs wrap the StrongBox failure as a generic ProviderException.
            }
        }
        generateWrappingKey(strongBox = false, bound = options.bindWrappingKeyToBiometric)
    }

    private fun generateWrappingKey(strongBox: Boolean, bound: Boolean) {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            PROVIDER_ANDROID_KEYSTORE,
        )
        val builder = KeyGenParameterSpec.Builder(
            WRAPPING_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (bound) {
            builder.setUserAuthenticationRequired(true)
            // Time-bound auth: a single biometric prompt unlocks the key
            // for AUTH_VALIDITY_SECONDS so a wallet session doesn't
            // re-prompt on every sign. The DEVICE_CREDENTIAL flag lets
            // the user fall back to phone PIN/pattern if biometrics
            // aren't available (sensor dirty, finger wet, etc.).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
            // If the user re-enrolls biometrics, invalidate the key; the
            // app must surface a re-initialise flow. Better that than
            // silently weakening security.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }
        } else {
            builder.setUserAuthenticationRequired(false)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        generator.generateKey()
    }

    private fun preferStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(
                "android.hardware.strongbox_keystore",
            )

    @Volatile private var cachedWrappingKey: SecretKey? = null

    private fun wrappingKey(): SecretKey {
        // Memoize. KeyStore.getEntry hits the OS keystore, which can
        // run ~1ms each call — across wrap+unwrap on every seed access
        // that added up to a measurable fraction of handshake startup.
        cachedWrappingKey?.let { return it }
        val ks = keystoreInstance()
        val entry = ks.getEntry(WRAPPING_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey.also { cachedWrappingKey = it }
    }

    /** Invalidate the cached wrapping key. Called when the keystore
     *  identity is rotated/reset, so subsequent ops fetch the new
     *  binding rather than the stale reference. */
    @Synchronized
    internal fun invalidateWrappingKeyCache() {
        cachedWrappingKey = null
    }

    private fun loadOrCreateSeed(): ByteArray {
        ensureWrappingKey()
        val file = seedFile
        return if (file.exists()) {
            unwrapSeed(file.readBytes())
        } else {
            val fresh = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
            try {
                val blob = wrapSeed(fresh)
                file.parentFile?.mkdirs()
                file.writeBytes(blob)
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                fresh.copyOf()
            } finally {
                fresh.fill(0)
            }
        }
    }

    private fun wrapSeed(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        // Format: [iv_len: 1][iv][ct]
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return out
    }

    private fun unwrapSeed(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty()) { "seed blob is empty" }
        val ivLen = blob[0].toInt() and 0xFF
        require(blob.size > 1 + ivLen) { "seed blob truncated" }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    // --------------------------------------------------------------------
    // Backing detection
    // --------------------------------------------------------------------

    private fun detectBacking(): KeystoreManager.Backing {
        return try {
            ensureWrappingKey()
            val ks = keystoreInstance()
            val entry = ks.getEntry(WRAPPING_ALIAS, null) as KeyStore.SecretKeyEntry
            val factory = javax.crypto.SecretKeyFactory.getInstance(
                entry.secretKey.algorithm,
                PROVIDER_ANDROID_KEYSTORE,
            )
            val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java) as KeyInfo
            when {
                isStrongBoxBacked(info) -> KeystoreManager.Backing.STRONGBOX
                isHardwareBacked(info) -> KeystoreManager.Backing.TEE
                else -> KeystoreManager.Backing.SOFTWARE_REJECTED
            }
        } catch (_: StrongBoxUnavailableException) {
            // Wrapping key was created without StrongBox; treat as TEE.
            KeystoreManager.Backing.TEE
        } catch (_: Throwable) {
            KeystoreManager.Backing.SOFTWARE_REJECTED
        }
    }

    private fun isStrongBoxBacked(info: KeyInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX

    @Suppress("DEPRECATION")
    private fun isHardwareBacked(info: KeyInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            info.isInsideSecureHardware
        }

    // --------------------------------------------------------------------
    // HKDF-SHA256
    // --------------------------------------------------------------------

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

    private fun keystoreInstance(): KeyStore =
        KeyStore.getInstance(PROVIDER_ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Defensive-copy wrapper for the cached public key. The constructor
     * copies the input, and every [publicKey] read returns a fresh copy,
     * so an upstream caller that zeroes "their" array cannot corrupt
     * this singleton's cache. The 32-byte copy on every access is
     * negligible compared to the cost of a corrupted identity.
     */
    class WysprIdentityHandle internal constructor(
        publicKey: ByteArray,
        override val alias: String,
    ) : KeystoreManager.IdentityKeyHandle {
        private val cachedPublicKey: ByteArray = publicKey.copyOf()

        override val publicKey: ByteArray get() = cachedPublicKey.copyOf()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WysprIdentityHandle) return false
            return alias == other.alias && cachedPublicKey.contentEquals(other.cachedPublicKey)
        }
        override fun hashCode(): Int =
            31 * alias.hashCode() + cachedPublicKey.contentHashCode()
    }

    private companion object {
        const val IDENTITY_ALIAS = "wyspr.identity.v1"
        const val WRAPPING_ALIAS = "wyspr.identity.wrap.v1"
        const val PROVIDER_ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SEED_FILENAME = "wyspr.identity.seed.v1"
        const val SEED_BYTES = 32
        const val GCM_TAG_BITS = 128
        const val X25519_KEY_BYTES = 32

        /**
         * How long a biometric prompt unlocks the wrapping key. Five
         * minutes is enough to cover a wallet session without re-
         * prompting every send, and short enough that a stolen unlocked
         * device can't drain the account.
         */
        const val AUTH_VALIDITY_SECONDS = 300

        // Fixed, application-scoped HKDF salt. Subkeys are namespaced via the
        // `info` parameter the caller supplies. PROTOCOLS.md §5.
        val HKDF_SALT = "WYSPR/v1/HKDF-SALT".encodeToByteArray()
    }
}

/**
 * Specific exception surface raised when the user has re-enrolled their
 * biometric since the wrapping key was created. The key is permanently
 * dead; the app must offer the user a "reset identity" path because
 * neither a new biometric prompt nor anything else will recover it.
 *
 * This is just a re-export of the platform exception so callers in
 * upper layers don't need to import it from android.security.keystore
 * (which would propagate that namespace through their imports). Catching
 * the platform exception directly works too.
 */
typealias KeystoreInvalidated = KeyPermanentlyInvalidatedException

typealias KeystoreAuthRequired = UserNotAuthenticatedException
