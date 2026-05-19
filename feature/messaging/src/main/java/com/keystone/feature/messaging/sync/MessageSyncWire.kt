package com.keystone.feature.messaging.sync

import com.keystone.core.crypto.Cbor
import com.keystone.feature.messaging.MessageEnvelope

/**
 * Wire format for the message-sync protocol that runs once both
 * sides have established a Noise transport session. Each frame is
 * CBOR-encoded and emitted as one [com.keystone.core.transport.Link.send]
 * call, after passing through `NoiseSession.encrypt(...)` on the
 * sender's side and `decrypt(...)` on the receiver's.
 *
 * The protocol is deliberately tiny:
 *
 *     Initiator                       Responder
 *     ---------                       ---------
 *     PUSH(env...)        -->
 *                         <--         ACK(id...)
 *                         <--         PUSH(env...)   (if responder has pending)
 *     ACK(id...)          -->
 *     END                 -->
 *                         <--         END
 *
 * Three frame variants:
 *
 *   tag=0 → Push(envelopes: array of MessageEnvelope.wireBytes)
 *   tag=1 → Ack(ids: array of message ids, each 16 bytes)
 *   tag=2 → End
 *
 * Top-level encoding: a CBOR array `[tag, payload?]`.
 *   - tag=0/1 → 2-element array; payload is itself a CBOR array of
 *     bytes-blobs.
 *   - tag=2 → 1-element array.
 */
internal sealed interface MessageSyncFrame {

    data class Push(val envelopes: List<MessageEnvelope>) : MessageSyncFrame
    data class Ack(val ids: List<ByteArray>) : MessageSyncFrame
    /**
     * "I have read the messages with these ids." Receiver looks up
     * each id in its OUTBOUND table and flips status to "read".
     * Sent after Push/Ack in the same sync round; acknowledged via
     * the same [Ack] frame the Push uses.
     */
    data class Read(val ids: List<ByteArray>) : MessageSyncFrame
    data object End : MessageSyncFrame

    fun wireBytes(): ByteArray = when (this) {
        is Push -> Cbor.encode {
            arrayHeader(2)
            uint(TAG_PUSH.toLong())
            arrayHeader(envelopes.size)
            for (env in envelopes) bytes(env.wireBytes())
        }
        is Ack -> Cbor.encode {
            arrayHeader(2)
            uint(TAG_ACK.toLong())
            arrayHeader(ids.size)
            for (id in ids) bytes(id)
        }
        is Read -> Cbor.encode {
            arrayHeader(2)
            uint(TAG_READ.toLong())
            arrayHeader(ids.size)
            for (id in ids) bytes(id)
        }
        is End -> Cbor.encode {
            arrayHeader(1)
            uint(TAG_END.toLong())
        }
    }

    companion object {
        const val TAG_PUSH = 0
        const val TAG_ACK = 1
        const val TAG_END = 2
        const val TAG_READ = 3

        /**
         * Inverse of [wireBytes]. Hard-fails on any structural
         * mismatch — a peer that ships malformed sync frames is
         * either misbehaving or attacking.
         */
        fun fromWire(bytes: ByteArray): MessageSyncFrame = Cbor.decode(bytes) {
            val outerLen = arrayHeader()
            require(outerLen in 1..2) { "frame outer array must be 1 or 2 elements" }
            val tag = uint().toInt()
            when (tag) {
                TAG_PUSH -> {
                    require(outerLen == 2) { "Push frame missing payload" }
                    val count = arrayHeader()
                    require(count in 0..MAX_BATCH) {
                        "Push count $count out of range"
                    }
                    val envelopes = ArrayList<MessageEnvelope>(count)
                    repeat(count) {
                        val envBytes = bytes()
                        envelopes.add(MessageEnvelope.fromWire(envBytes))
                    }
                    Push(envelopes)
                }
                TAG_ACK -> {
                    require(outerLen == 2) { "Ack frame missing payload" }
                    val count = arrayHeader()
                    require(count in 0..MAX_BATCH) { "Ack count $count out of range" }
                    val ids = ArrayList<ByteArray>(count)
                    repeat(count) {
                        val id = bytes()
                        require(id.size == MessageEnvelope.ID_LENGTH) {
                            "ack id must be ${MessageEnvelope.ID_LENGTH} bytes"
                        }
                        ids.add(id)
                    }
                    Ack(ids)
                }
                TAG_READ -> {
                    require(outerLen == 2) { "Read frame missing payload" }
                    val count = arrayHeader()
                    require(count in 0..MAX_BATCH) { "Read count $count out of range" }
                    val ids = ArrayList<ByteArray>(count)
                    repeat(count) {
                        val id = bytes()
                        require(id.size == MessageEnvelope.ID_LENGTH) {
                            "read id must be ${MessageEnvelope.ID_LENGTH} bytes"
                        }
                        ids.add(id)
                    }
                    Read(ids)
                }
                TAG_END -> {
                    require(outerLen == 1) { "End frame must have no payload" }
                    End
                }
                else -> error("unknown sync frame tag $tag")
            }
        }

        /** Hard cap to defend against a hostile peer claiming a huge array. */
        const val MAX_BATCH = 1_000
    }
}
