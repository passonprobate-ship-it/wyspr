package com.wyspr.feature.messaging.sync

import com.wyspr.core.transport.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [SyncTrigger] (declared in `core:transport:api`) to
 * [MessageSyncService]. Lets non-messaging features — currently the
 * Events screen — drive a sync round without depending on
 * `feature:messaging` directly.
 *
 * Fire-and-forget: a request while a round is already running is
 * dropped (the in-flight round will pick up anything new), so a
 * periodic caller can't pile rounds onto [MessageSyncService]'s lock.
 */
@Singleton
class MessageSyncTrigger @Inject constructor(
    private val syncService: MessageSyncService,
) : SyncTrigger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = AtomicBoolean(false)

    override fun requestSync() {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                syncService.runOnce()
            } catch (_: Throwable) {
                // Best-effort: a failed round is retried by the next tick.
            } finally {
                inFlight.set(false)
            }
        }
    }
}
