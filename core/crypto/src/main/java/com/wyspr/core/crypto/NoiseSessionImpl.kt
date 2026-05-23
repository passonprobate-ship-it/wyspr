package com.wyspr.core.crypto

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.HandshakeState

/**
 * Real implementation of [NoiseSession] backed by rweather/noise-java.
 *
 * Pattern is hard-wired to `Noise_XX_25519_ChaChaPoly_BLAKE2s`. The
 * prologue includes [PROLOGUE_PREFIX] plus caller-supplied bytes
 * (community id + both QR nonces — PROTOCOLS.md §3.2), so a MITM that
 * substitutes a different community or replays QR nonces fails the
 * handshake hash check on the third message.
 *
 * Lifecycle is one-shot. After [close], the underlying Noise objects
 * are destroyed and the session cannot be restarted; the caller
 * discards the reference.
 *
 * Ed25519 vs X25519: Wyspr identities are Ed25519 so they can sign
 * invitations. Noise XX needs X25519 static keys. Both keys are
 * derived from the same hardware-protected seed via libsodium's
 * Ed25519 → X25519 conversion. The X25519 secret never lives longer
 * than this object's [start] call. See [KeystoreManager.deriveStaticX25519].
 */
class NoiseSessionImpl(
    override val role: NoiseSession.Role,
) : NoiseSession {

    private var handshake: HandshakeState? = null
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null
    private var _state: NoiseSession.State = NoiseSession.State.Idle
    private var cachedHash: ByteArray? = null
    private var cachedRemoteStatic: ByteArray? = null

    override val state: NoiseSession.State get() = _state

    override fun start(prologue: ByteArray, keystore: KeystoreManager) {
        check(_state == NoiseSession.State.Idle) { "NoiseSession already started" }
        val noiseRole = when (role) {
            NoiseSession.Role.Initiator -> HandshakeState.INITIATOR
            NoiseSession.Role.Responder -> HandshakeState.RESPONDER
        }
        val hs = try {
            HandshakeState(NoiseSession.PATTERN, noiseRole)
        } catch (t: Throwable) {
            _state = NoiseSession.State.Failed
            throw IllegalStateException(
                "noise-java could not initialise pattern ${NoiseSession.PATTERN}",
                t,
            )
        }

        val fullPrologue = NoiseSession.PROLOGUE_PREFIX.encodeToByteArray() + prologue
        hs.setPrologue(fullPrologue, 0, fullPrologue.size)

        val (xPub, xSec) = keystore.deriveStaticX25519()
        try {
            val localDh: DHState = hs.localKeyPair
            check(localDh.privateKeyLength == xSec.size) {
                "library private-key length mismatch: expected ${localDh.privateKeyLength}, got ${xSec.size}"
            }
            check(localDh.publicKeyLength == xPub.size) {
                "library public-key length mismatch: expected ${localDh.publicKeyLength}, got ${xPub.size}"
            }
            localDh.setPrivateKey(xSec, 0)
        } finally {
            xSec.fill(0)
        }

        hs.start()
        handshake = hs
        _state = NoiseSession.State.Handshake
    }

    override fun writeHandshakeMessage(payload: ByteArray): ByteArray {
        val hs = requireHandshake()
        val buffer = ByteArray(NoiseSession.MAX_FRAME_BYTES)
        val written = hs.writeMessage(buffer, 0, payload, 0, payload.size)
        if (hs.action == HandshakeState.SPLIT) doSplit(hs)
        return buffer.copyOf(written)
    }

    override fun readHandshakeMessage(message: ByteArray): ByteArray? {
        val hs = requireHandshake()
        val payloadBuf = ByteArray(message.size)
        val payloadLen = hs.readMessage(message, 0, message.size, payloadBuf, 0)
        if (hs.action == HandshakeState.SPLIT) doSplit(hs)
        return if (payloadLen == 0) null else payloadBuf.copyOf(payloadLen)
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        check(_state == NoiseSession.State.Transport) { "Noise not in transport mode" }
        val cs = sendCipher ?: error("send cipher not initialised")
        if (plaintext.size <= NOISE_MAX_PLAINTEXT) {
            val out = ByteArray(plaintext.size + cs.macLength)
            val n = cs.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
            return out.copyOf(n)
        }
        val bos = java.io.ByteArrayOutputStream(plaintext.size + 256)
        bos.write(CHUNK_MAGIC)
        var offset = 0
        while (offset < plaintext.size) {
            val len = minOf(NOISE_MAX_PLAINTEXT, plaintext.size - offset)
            val out = ByteArray(len + cs.macLength)
            val n = cs.encryptWithAd(null, plaintext, offset, out, 0, len)
            writeU32(bos, n)
            bos.write(out, 0, n)
            offset += len
        }
        return bos.toByteArray()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        check(_state == NoiseSession.State.Transport) { "Noise not in transport mode" }
        val cs = receiveCipher ?: error("receive cipher not initialised")
        if (!startsWithChunkMagic(ciphertext)) {
            val out = ByteArray(ciphertext.size)
            val n = cs.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
            return out.copyOf(n)
        }
        val bos = java.io.ByteArrayOutputStream(ciphertext.size)
        var pos = CHUNK_MAGIC.size
        while (pos < ciphertext.size) {
            require(pos + 4 <= ciphertext.size) { "truncated chunk header" }
            val chunkLen = readU32(ciphertext, pos)
            pos += 4
            require(pos + chunkLen <= ciphertext.size) { "chunk overruns input" }
            val out = ByteArray(chunkLen)
            val n = cs.decryptWithAd(null, ciphertext, pos, out, 0, chunkLen)
            bos.write(out, 0, n)
            pos += chunkLen
        }
        return bos.toByteArray()
    }

    override val handshakeHash: ByteArray
        get() = cachedHash?.copyOf()
            ?: error("handshake hash not available until session reaches Transport")

    override val remoteStaticPublicKey: ByteArray
        get() = cachedRemoteStatic?.copyOf()
            ?: error("remote static not available until session reaches Transport")

    override fun close() {
        runCatching { handshake?.destroy() }
        runCatching { sendCipher?.destroy() }
        runCatching { receiveCipher?.destroy() }
        handshake = null
        sendCipher = null
        receiveCipher = null
        cachedHash?.fill(0)
        cachedHash = null
        cachedRemoteStatic = null
        _state = NoiseSession.State.Closed
    }

    private fun requireHandshake(): HandshakeState =
        handshake?.also {
            check(_state == NoiseSession.State.Handshake) { "wrong state: $_state" }
        } ?: error("Noise session not started")

    private fun doSplit(hs: HandshakeState) {
        cachedHash = hs.handshakeHash.copyOf()
        val remote: DHState = hs.remotePublicKey
        val remoteBytes = ByteArray(remote.publicKeyLength)
        remote.getPublicKey(remoteBytes, 0)
        cachedRemoteStatic = remoteBytes
        val pair = hs.split()
        sendCipher = pair.sender
        receiveCipher = pair.receiver
        hs.destroy()
        handshake = null
        _state = NoiseSession.State.Transport
    }

    companion object {
        private const val NOISE_MAX_MESSAGE = 65535
        private const val NOISE_MAX_PLAINTEXT = NOISE_MAX_MESSAGE - 16
        private val CHUNK_MAGIC = byteArrayOf(0xFF.toByte(), 0x57, 0x43, 0x48)

        private fun startsWithChunkMagic(data: ByteArray): Boolean {
            if (data.size < CHUNK_MAGIC.size) return false
            return data[0] == CHUNK_MAGIC[0] && data[1] == CHUNK_MAGIC[1] &&
                data[2] == CHUNK_MAGIC[2] && data[3] == CHUNK_MAGIC[3]
        }

        private fun writeU32(out: java.io.ByteArrayOutputStream, value: Int) {
            out.write((value ushr 24) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        private fun readU32(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
