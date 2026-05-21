package com.keystone.feature.messaging.sync

import com.keystone.core.crypto.Cbor
import com.keystone.feature.messaging.MessageEnvelope
import com.keystone.feature.messaging.groups.GroupMembership
import com.keystone.feature.messaging.groups.GroupMessageEnvelope
import com.keystone.feature.messaging.mailbox.MailboxBinding
import com.keystone.feature.messaging.mailbox.MailboxEnvelope

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

    /**
     * The Push frame carries BOTH 1:1 envelopes and group envelopes in
     * the same round trip. The Ack frame's id list covers both — ids
     * are 16 bytes either way and the receiver looks each id up by
     * the table it lives in.
     *
     * Wire shape: `[ tag=0, [env_bytes…], [group_env_bytes…] ]`. The
     * group array is always present, possibly empty, to keep the
     * structure stable.
     */
    data class Push(
        val envelopes: List<MessageEnvelope>,
        val groupEnvelopes: List<GroupMessageEnvelope> = emptyList(),
        /**
         * `GroupMembership` certs the local device knows that the peer
         * may not. Propagating these inline with the messages avoids a
         * separate round-trip: when the peer receives a group message,
         * the cert proving the sender is a member is already in the
         * same Push frame.
         */
        val membershipCerts: List<GroupMembership> = emptyList(),
        /**
         * Phase 3a — propagate `MailboxBinding` certs through the
         * sync round so peers learn each other's mailbox without an
         * out-of-band exchange. Typically a single cert (the local
         * user's own) but the format admits multiple in case we
         * relay-forward learned bindings in a future revision.
         */
        val mailboxBindings: List<MailboxBinding> = emptyList(),
        /**
         * Phase 3b — sealed envelopes the sender wants this peer to
         * STORE AS A MAILBOX. The peer only does anything with these
         * if "Be a mailbox" is enabled; otherwise the list is silently
         * dropped (and unacked, so the sender keeps retrying through
         * other paths).
         */
        val mailboxEnvelopes: List<MailboxEnvelope> = emptyList(),
    ) : MessageSyncFrame
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
            arrayHeader(6)
            uint(TAG_PUSH.toLong())
            arrayHeader(envelopes.size)
            for (env in envelopes) bytes(env.wireBytes())
            arrayHeader(groupEnvelopes.size)
            for (env in groupEnvelopes) bytes(env.wireBytes())
            arrayHeader(membershipCerts.size)
            for (cert in membershipCerts) bytes(cert.wireBytes())
            arrayHeader(mailboxBindings.size)
            for (b in mailboxBindings) bytes(b.wireBytes())
            arrayHeader(mailboxEnvelopes.size)
            for (env in mailboxEnvelopes) bytes(env.wireBytes())
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
            require(outerLen in 1..6) { "frame outer array must be 1..6 elements" }
            val tag = uint().toInt()
            when (tag) {
                TAG_PUSH -> {
                    // Push wire format has grown over time. Decoder accepts
                    // every prior shape so a sender on an older build
                    // stays interoperable while the cluster upgrades:
                    //   v0.7.2  → 2-element  (envelopes)
                    //   v0.8    → 3-element  (+groupEnvelopes)
                    //   v0.8    → 4-element  (+membershipCerts)
                    //   v0.9    → 5-element  (+mailboxBindings)
                    //   v0.9    → 6-element  (+mailboxEnvelopes)
                    // Encoder always writes the current 6-element shape.
                    require(outerLen in 2..6) {
                        "Push frame must be 2..6 elements"
                    }
                    val count = arrayHeader()
                    require(count in 0..MAX_BATCH) {
                        "Push count $count out of range"
                    }
                    val envelopes = ArrayList<MessageEnvelope>(count)
                    repeat(count) {
                        val envBytes = bytes()
                        envelopes.add(MessageEnvelope.fromWire(envBytes))
                    }
                    val groupEnvelopes: List<GroupMessageEnvelope> = if (outerLen >= 3) {
                        val groupCount = arrayHeader()
                        require(groupCount in 0..MAX_BATCH) {
                            "Group push count $groupCount out of range"
                        }
                        ArrayList<GroupMessageEnvelope>(groupCount).also { list ->
                            repeat(groupCount) {
                                val envBytes = bytes()
                                list.add(GroupMessageEnvelope.fromWire(envBytes))
                            }
                        }
                    } else {
                        emptyList()
                    }
                    val membershipCerts: List<GroupMembership> = if (outerLen >= 4) {
                        val certCount = arrayHeader()
                        require(certCount in 0..MAX_BATCH) {
                            "Membership cert count $certCount out of range"
                        }
                        ArrayList<GroupMembership>(certCount).also { list ->
                            repeat(certCount) {
                                val certBytes = bytes()
                                list.add(GroupMembership.fromWire(certBytes))
                            }
                        }
                    } else {
                        emptyList()
                    }
                    val mailboxBindings: List<MailboxBinding> = if (outerLen >= 5) {
                        val bindCount = arrayHeader()
                        require(bindCount in 0..MAX_BATCH) {
                            "Mailbox binding count $bindCount out of range"
                        }
                        ArrayList<MailboxBinding>(bindCount).also { list ->
                            repeat(bindCount) {
                                val certBytes = bytes()
                                list.add(MailboxBinding.fromWire(certBytes))
                            }
                        }
                    } else {
                        emptyList()
                    }
                    val mailboxEnvelopes: List<MailboxEnvelope> = if (outerLen >= 6) {
                        val mxCount = arrayHeader()
                        require(mxCount in 0..MAX_BATCH) {
                            "Mailbox envelope count $mxCount out of range"
                        }
                        ArrayList<MailboxEnvelope>(mxCount).also { list ->
                            repeat(mxCount) {
                                val envBytes = bytes()
                                list.add(MailboxEnvelope.fromWire(envBytes))
                            }
                        }
                    } else {
                        emptyList()
                    }
                    Push(envelopes, groupEnvelopes, membershipCerts, mailboxBindings, mailboxEnvelopes)
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
