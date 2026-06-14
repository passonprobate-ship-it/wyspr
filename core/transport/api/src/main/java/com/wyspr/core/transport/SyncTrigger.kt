package com.wyspr.core.transport

/**
 * A fire-and-forget request to run one anti-entropy sync round now.
 *
 * Lives in `core:transport:api` so feature modules that are NOT
 * `feature:messaging` (e.g. `feature:coordination`) can kick a sync
 * without reaching sideways across the module graph. The messaging
 * layer provides the real implementation; everyone else depends only
 * on this interface.
 *
 * Coordination events and RSVPs piggyback on the message-sync round,
 * but message sync historically only ran from the chat screens. The
 * Events screen calls [requestSync] on a timer so a user sitting on
 * the Events list actually receives newly-shared events.
 */
interface SyncTrigger {

    /** Launch a sync round if one isn't already in flight. Safe to call repeatedly. */
    fun requestSync()

    object NoOp : SyncTrigger {
        override fun requestSync() = Unit
    }
}
