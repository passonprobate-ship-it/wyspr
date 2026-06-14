package com.wyspr.core.transport

/**
 * Surfaces a system notification when a coordination event arrives
 * from a peer over the sync protocol. Mirrors [MessagingNotifier] and
 * [PaymentNotifier]: the interface lives here in `core:transport:api`
 * so the sync layer can fire it without depending on `:app`, and the
 * Android implementation is bound in via Hilt.
 */
interface CoordinationNotifier {

    /**
     * A previously-unseen event was ingested. [eventId] is the raw
     * event id (used to build the deep-link into the Events area);
     * [title] is the event's display title.
     */
    fun notifyReceivedEvent(eventId: ByteArray, title: String)

    object NoOp : CoordinationNotifier {
        override fun notifyReceivedEvent(eventId: ByteArray, title: String) = Unit
    }
}
