package com.wyspr.core.crypto

/**
 * Wrapper around the noise-java library, fixed to one pattern:
 *
 *     Noise_XX_25519_ChaChaPoly_BLAKE2s
 *
 * Why XX:
 *  - Mutual authentication.
 *  - Identity keys are exchanged during the handshake; neither side needs
 *    prior knowledge of the other's static key.
 *  - Forward secrecy via per-session ephemeral keys.
 *
 * Lifecycle:
 *     Idle -- start() --> Handshake -- read/write messages --> Transport
 *                                                                  |
 *                                                            encrypt/decrypt
 *                                                                  |
 *                                                                close()
 */
interface NoiseSession {

    val role: Role
    val state: State

    /**
     * Begin the XX handshake. The prologue is mixed into the handshake
     * hash; both sides MUST use the same bytes. PROTOCOLS.md §3.2 derives
     * it from the protocol prefix + community id + both QR nonces.
     *
     * The keystore is the source of the local static X25519 keypair (via
     * [KeystoreManager.deriveStaticX25519]). The Ed25519 identity and
     * the X25519 static derive from the same hardware-protected seed.
     */
    fun start(prologue: ByteArray, keystore: KeystoreManager)

    /** Read an incoming handshake message. Returns optional payload. */
    fun readHandshakeMessage(message: ByteArray): ByteArray?

    /**
     * Produce the next handshake message. [payload] is optional and is
     * transported encrypted from XX message 2 onward. Returns the wire bytes.
     */
    fun writeHandshakeMessage(payload: ByteArray = EMPTY): ByteArray

    /** Encrypt a frame in transport mode. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Decrypt a frame in transport mode. Throws on auth failure. */
    fun decrypt(ciphertext: ByteArray): ByteArray

    /** The handshake hash; usable for channel binding. Available after state == Transport. */
    val handshakeHash: ByteArray

    /** The remote party's static public key (32 bytes). Available after state == Transport. */
    val remoteStaticPublicKey: ByteArray

    /** Wipe key material and tear down. */
    fun close()

    enum class Role { Initiator, Responder }

    enum class State { Idle, Handshake, Transport, Closed, Failed }

    companion object {
        private val EMPTY = ByteArray(0)
        const val PATTERN = "Noise_XX_25519_ChaChaPoly_BLAKE2s"
        const val PROLOGUE_PREFIX = "WYSPR/v1"
        const val MAX_FRAME_BYTES = 262_144
    }
}
