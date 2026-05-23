package com.keystone.app.transport

import android.content.Context
import com.keystone.core.transport.ForegroundClaim
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level binding of [ForegroundClaim] that translates each
 * start/stop call into a [TransportForegroundService] intent.
 * Lets `:feature:monero-wallet` (and any future low-layer module)
 * keep Android from killing its background work without taking a
 * direct dep on the service.
 */
@Singleton
class FgsForegroundClaim @Inject constructor(
    @ApplicationContext private val context: Context,
) : ForegroundClaim {

    override fun start(reason: ForegroundClaim.Reason) {
        TransportForegroundService.start(context, reason.toServiceReason())
    }

    override fun stop(reason: ForegroundClaim.Reason) {
        // The service's ref-count owns the stop semantics; the
        // reason carried in the intent is unused for STOP. We
        // accept it on this interface to mirror start() and to
        // leave room for per-reason refcounts later.
        TransportForegroundService.stop(context)
    }

    private fun ForegroundClaim.Reason.toServiceReason(): TransportForegroundService.Reason =
        when (this) {
            ForegroundClaim.Reason.Handshake -> TransportForegroundService.Reason.Handshake
            ForegroundClaim.Reason.Sharing -> TransportForegroundService.Reason.Sharing
            ForegroundClaim.Reason.Transport -> TransportForegroundService.Reason.Transport
            ForegroundClaim.Reason.WalletSync -> TransportForegroundService.Reason.WalletSync
        }
}
