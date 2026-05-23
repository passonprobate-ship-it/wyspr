package com.wyspr.feature.messaging.mailbox

import com.wyspr.core.crypto.Cbor

/**
 * Wire format for the mailbox protocol. Frames run over the same
 * Noise transport that carries [com.wyspr.feature.messaging.sync.MessageSyncFrame]s
 * — Phase 3 wires this into [com.wyspr.feature.messaging.sync.MessageSyncService]
 * so a single Noise session can carry both messaging and mailbox
 * traffic.
 *
 * Tags 10–13 are reserved here so they don't collide with the
 * messaging frame tags (0–3). A peer that reads a tag it doesn't
 * understand drops the frame and the session — there is no
 * "unknown frame, ignore" path.
 *
 *   tag=10 → Push(envelope: MailboxEnvelope.wireBytes)
 *            Sender pushes a sealed envelope to the host. Host
 *            verifies + stores + acks (PushAck).
 *
 *   tag=11 → Pull(sinceCursor?: bytes)
 *            Recipient asks the host for every envelope stored for
 *            THE NOISE PEER's pub created after sinceCursor (empty
 *            on first pull = give me everything).
 *
 *   tag=12 → PullResponse(envelopes: [MailboxEnvelope.wireBytes],
 *                          maxCursor: bytes)
 *            Host returns up to MAX_BATCH envelopes oldest-first.
 *            maxCursor is the largest `created_at` (8-byte big-
 *            endian uint) seen in this batch, OR the sinceCursor if
 *            the batch was empty.
 *
 *   tag=13 → Ack(ids: [16 bytes])
 *            Recipient confirms receipt — host deletes the named
 *            rows. Also used by the host to acknowledge a Push (one
 *            id only).
 *
 *   tag=14 → End
 *            Either side. Closes the mailbox phase of the session.
 *
 * Wire framing (CBOR arrays — the leading uint is the tag):
 *   - Push          {10, env_bytes}
 *   - Pull          {11, since_cursor_bytes}     // empty bytes on first pull
 *   - PullResponse  {12, array{env_bytes}, max_cursor}
 *   - Ack           {13, array{id_bytes}}
 *   - End           {14}
 */
internal sealed interface MailboxFrame {

    data class Push(val envelope: MailboxEnvelope) : MailboxFrame
    data class Pull(val sinceCursor: ByteArray) : MailboxFrame {
        override fun equals(other: Any?): Boolean =
            other is Pull && sinceCursor.contentEquals(other.sinceCursor)
        override fun hashCode(): Int = sinceCursor.contentHashCode()
    }
    data class PullResponse(
        val envelopes: List<MailboxEnvelope>,
        val maxCursor: ByteArray,
    ) : MailboxFrame {
        override fun equals(other: Any?): Boolean {
            if (other !is PullResponse) return false
            if (!maxCursor.contentEquals(other.maxCursor)) return false
            if (envelopes.size != other.envelopes.size) return false
            return envelopes.zip(other.envelopes).all { (a, b) -> a == b }
        }
        override fun hashCode(): Int {
            var r = maxCursor.contentHashCode()
            for (env in envelopes) r = 31 * r + env.hashCode()
            return r
        }
    }
    data class Ack(val ids: List<ByteArray>) : MailboxFrame {
        override fun equals(other: Any?): Boolean {
            if (other !is Ack) return false
            if (ids.size != other.ids.size) return false
            return ids.zip(other.ids).all { (a, b) -> a.contentEquals(b) }
        }
        override fun hashCode(): Int {
            var r = ids.size
            for (id in ids) r = 31 * r + id.contentHashCode()
            return r
        }
    }
    data object End : MailboxFrame

    fun wireBytes(): ByteArray = when (this) {
        is Push -> Cbor.encode {
            arrayHeader(2)
            uint(TAG_PUSH.toLong())
            bytes(envelope.wireBytes())
        }
        is Pull -> Cbor.encode {
            arrayHeader(2)
            uint(TAG_PULL.toLong())
            bytes(sinceCursor)
        }
        is PullResponse -> Cbor.encode {
            arrayHeader(3)
            uint(TAG_PULL_RESPONSE.toLong())
            arrayHeader(envelopes.size)
            for (env in envelopes) bytes(env.wireBytes())
            bytes(maxCursor)
        }
        is Ack -> Cbor.encode {
            arrayHeader(2)
            uint(TAG_ACK.toLong())
            arrayHeader(ids.size)
            for (id in ids) bytes(id)
        }
        is End -> Cbor.encode {
            arrayHeader(1)
            uint(TAG_END.toLong())
        }
    }

    companion object {
        const val TAG_PUSH = 10
        const val TAG_PULL = 11
        const val TAG_PULL_RESPONSE = 12
        const val TAG_ACK = 13
        const val TAG_END = 14

        /** Hard cap on a single batch's envelope count. Bounds memory + frame size. */
        const val MAX_BATCH = 64

        /** Hard cap on the cursor field — 8 bytes big-endian uint of `created_at`. */
        const val CURSOR_LENGTH = 8

        /**
         * Inverse of [wireBytes]. Hard-fails on any structural mismatch.
         * A peer that ships malformed frames is misbehaving or attacking —
         * caller drops the session.
         */
        fun fromWire(blob: ByteArray): MailboxFrame = Cbor.decode(blob) {
            val outerLen = arrayHeader()
            require(outerLen in 1..3) { "mailbox frame outer array out of range: $outerLen" }
            val tag = uint().toInt()
            when (tag) {
                TAG_PUSH -> {
                    require(outerLen == 2) { "Push must be 2 elements" }
                    val env = MailboxEnvelope.fromWire(bytes())
                    Push(env)
                }
                TAG_PULL -> {
                    require(outerLen == 2) { "Pull must be 2 elements" }
                    val cursor = bytes()
                    require(cursor.size == 0 || cursor.size == CURSOR_LENGTH) {
                        "Pull cursor must be empty or $CURSOR_LENGTH bytes"
                    }
                    Pull(cursor)
                }
                TAG_PULL_RESPONSE -> {
                    require(outerLen == 3) { "PullResponse must be 3 elements" }
                    val count = arrayHeader()
                    require(count in 0..MAX_BATCH) { "PullResponse count $count out of range" }
                    val envs = ArrayList<MailboxEnvelope>(count)
                    repeat(count) {
                        envs.add(MailboxEnvelope.fromWire(bytes()))
                    }
                    val maxCursor = bytes()
                    require(maxCursor.size == CURSOR_LENGTH) {
                        "PullResponse cursor must be $CURSOR_LENGTH bytes"
                    }
                    PullResponse(envs, maxCursor)
                }
                TAG_ACK -> {
                    require(outerLen == 2) { "Ack must be 2 elements" }
                    val count = arrayHeader()
                    require(count in 0..MAX_BATCH) { "Ack count $count out of range" }
                    val ids = ArrayList<ByteArray>(count)
                    repeat(count) {
                        val id = bytes()
                        require(id.size == MailboxEnvelope.ID_LENGTH) {
                            "Ack id must be ${MailboxEnvelope.ID_LENGTH} bytes"
                        }
                        ids.add(id)
                    }
                    Ack(ids)
                }
                TAG_END -> {
                    require(outerLen == 1) { "End must have no payload" }
                    End
                }
                else -> error("unknown mailbox frame tag $tag")
            }
        }

        // ---- Cursor helpers ----
        //
        // Cursor is the 8-byte big-endian unsigned encoding of a
        // `created_at` second-stamp. Recipients persist the largest
        // cursor seen and re-pull from there next round; the host
        // returns rows where created_at > sinceCursor.

        fun encodeCursor(seconds: Long): ByteArray {
            val out = ByteArray(CURSOR_LENGTH)
            var v = seconds
            for (i in CURSOR_LENGTH - 1 downTo 0) {
                out[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            return out
        }

        fun decodeCursor(bytes: ByteArray): Long {
            require(bytes.size == CURSOR_LENGTH) { "cursor must be $CURSOR_LENGTH bytes" }
            var v = 0L
            for (i in 0 until CURSOR_LENGTH) {
                v = (v shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return v
        }

        /** Sentinel "before any envelope" cursor — used in [Pull] when first pulling. */
        val EMPTY_CURSOR = ByteArray(0)
    }
}
