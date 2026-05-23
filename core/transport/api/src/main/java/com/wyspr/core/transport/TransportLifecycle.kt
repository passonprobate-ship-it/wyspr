package com.wyspr.core.transport

/**
 * Acquire/release facade onto the app-level foreground service that
 * keeps the transport stack alive while a feature needs the radio.
 *
 * Feature ViewModels (`OnboardingViewModel`, `ApkSharingViewModel`)
 * inject this interface and call:
 *
 *   - [acquireForHandshake] when a BLE handshake is starting
 *   - [acquireForSharing]   when the share server is going up
 *   - [release]             on every exit path (success, abort, cancel,
 *                           VM clear, error)
 *
 * The implementation is reference-counted: multiple `acquire*` calls
 * stack and the service stays in foreground until a matching number
 * of [release] calls fire. A no-op default implementation exists so
 * unit tests don't need a real Android service.
 *
 * Lives in `core:transport:api` so feature modules don't have to
 * depend on `:app`; the app module binds the real implementation
 * via Hilt.
 */
interface TransportLifecycle {
    fun acquireForHandshake()
    fun acquireForSharing()
    fun release()

    /** No-op default — used in unit tests + as a Hilt fallback. */
    object NoOp : TransportLifecycle {
        override fun acquireForHandshake() = Unit
        override fun acquireForSharing() = Unit
        override fun release() = Unit
    }
}
