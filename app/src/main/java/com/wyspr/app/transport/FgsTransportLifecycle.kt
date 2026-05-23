package com.wyspr.app.transport

import android.content.Context
import com.wyspr.core.transport.TransportLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level binding of [TransportLifecycle] that translates each
 * acquire/release call into a [TransportForegroundService] intent.
 * The service itself owns the reference counting and decides when
 * to stop.
 */
@Singleton
class FgsTransportLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransportLifecycle {

    override fun acquireForHandshake() {
        TransportForegroundService.start(context, TransportForegroundService.Reason.Handshake)
    }

    override fun acquireForSharing() {
        TransportForegroundService.start(context, TransportForegroundService.Reason.Sharing)
    }

    override fun release() {
        TransportForegroundService.stop(context)
    }
}
