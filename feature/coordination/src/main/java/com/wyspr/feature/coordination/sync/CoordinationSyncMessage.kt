package com.wyspr.feature.coordination.sync

import com.wyspr.core.crypto.Cbor

sealed interface CoordinationSyncMessage {

    data class EventHaveSet(val pairs: List<Pair<ByteArray, Long>>) : CoordinationSyncMessage
    data class EventWant(val pairs: List<Pair<ByteArray, Long>>) : CoordinationSyncMessage
    data class EventPush(val envelopes: List<ByteArray>) : CoordinationSyncMessage

    data class RsvpHaveSet(val pairs: List<Pair<ByteArray, ByteArray>>) : CoordinationSyncMessage
    data class RsvpWant(val pairs: List<Pair<ByteArray, ByteArray>>) : CoordinationSyncMessage
    data class RsvpPush(val envelopes: List<ByteArray>) : CoordinationSyncMessage

    companion object {
        const val TAG_EVENT_HAVE_SET: Int = 0x40
        const val TAG_EVENT_WANT: Int = 0x41
        const val TAG_EVENT_PUSH: Int = 0x42
        const val TAG_RSVP_HAVE_SET: Int = 0x43
        const val TAG_RSVP_WANT: Int = 0x44
        const val TAG_RSVP_PUSH: Int = 0x45

        const val MAX_FRAME_BYTES: Int = 16_384

        fun encode(msg: CoordinationSyncMessage): ByteArray = Cbor.encode {
            when (msg) {
                is EventHaveSet -> {
                    arrayHeader(2)
                    uint(TAG_EVENT_HAVE_SET.toLong())
                    arrayHeader(msg.pairs.size)
                    for ((id, createdAt) in msg.pairs) {
                        arrayHeader(2)
                        bytes(id)
                        uint(createdAt)
                    }
                }
                is EventWant -> {
                    arrayHeader(2)
                    uint(TAG_EVENT_WANT.toLong())
                    arrayHeader(msg.pairs.size)
                    for ((id, createdAt) in msg.pairs) {
                        arrayHeader(2)
                        bytes(id)
                        uint(createdAt)
                    }
                }
                is EventPush -> {
                    arrayHeader(2)
                    uint(TAG_EVENT_PUSH.toLong())
                    arrayHeader(msg.envelopes.size)
                    for (env in msg.envelopes) bytes(env)
                }
                is RsvpHaveSet -> {
                    arrayHeader(2)
                    uint(TAG_RSVP_HAVE_SET.toLong())
                    arrayHeader(msg.pairs.size)
                    for ((eventId, responderPub) in msg.pairs) {
                        arrayHeader(2)
                        bytes(eventId)
                        bytes(responderPub)
                    }
                }
                is RsvpWant -> {
                    arrayHeader(2)
                    uint(TAG_RSVP_WANT.toLong())
                    arrayHeader(msg.pairs.size)
                    for ((eventId, responderPub) in msg.pairs) {
                        arrayHeader(2)
                        bytes(eventId)
                        bytes(responderPub)
                    }
                }
                is RsvpPush -> {
                    arrayHeader(2)
                    uint(TAG_RSVP_PUSH.toLong())
                    arrayHeader(msg.envelopes.size)
                    for (env in msg.envelopes) bytes(env)
                }
            }
        }

        fun decode(bytes: ByteArray): CoordinationSyncMessage = Cbor.decode(bytes) {
            val len = arrayHeader()
            require(len == 2) { "CoordinationSyncMessage must have 2 fields, got $len" }
            val tag = uint().toIntChecked()
            when (tag) {
                TAG_EVENT_HAVE_SET -> EventHaveSet(readIdLongPairs(this))
                TAG_EVENT_WANT -> EventWant(readIdLongPairs(this))
                TAG_EVENT_PUSH -> EventPush(readBlobs(this))
                TAG_RSVP_HAVE_SET -> RsvpHaveSet(readBlobPairs(this))
                TAG_RSVP_WANT -> RsvpWant(readBlobPairs(this))
                TAG_RSVP_PUSH -> RsvpPush(readBlobs(this))
                else -> error("Unknown coordination sync tag: 0x${tag.toString(16)}")
            }
        }

        private fun readIdLongPairs(r: Cbor.Reader): List<Pair<ByteArray, Long>> {
            val n = r.arrayHeader()
            val out = ArrayList<Pair<ByteArray, Long>>(n)
            repeat(n) {
                val pLen = r.arrayHeader()
                require(pLen == 2)
                out += r.bytes() to r.uint()
            }
            return out
        }

        private fun readBlobPairs(r: Cbor.Reader): List<Pair<ByteArray, ByteArray>> {
            val n = r.arrayHeader()
            val out = ArrayList<Pair<ByteArray, ByteArray>>(n)
            repeat(n) {
                val pLen = r.arrayHeader()
                require(pLen == 2)
                out += r.bytes() to r.bytes()
            }
            return out
        }

        private fun readBlobs(r: Cbor.Reader): List<ByteArray> {
            val n = r.arrayHeader()
            val out = ArrayList<ByteArray>(n)
            repeat(n) { out.add(r.bytes()) }
            return out
        }

        private fun Long.toIntChecked(): Int {
            require(this in 0..Int.MAX_VALUE.toLong()) { "value $this out of Int range" }
            return toInt()
        }
    }
}
