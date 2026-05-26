package com.wyspr.app

import android.app.Application
import com.wyspr.feature.messaging.mailbox.MailboxNotifyHost
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WysprApplication : Application() {

    // torBackend and profileHttpServer used to be @Inject fields here,
    // but they were unused — both are started lazily from MainActivity's
    // biometric-gated LaunchedEffect. Removing them avoids forcing eager
    // Hilt construction at Application.onCreate time.

    /**
     * Eager-instantiated so its init {} block runs at app start and
     * starts observing [MailboxSettings.hostEnabled]. Without this
     * forced injection the singleton would never be constructed
     * (nothing else holds a reference) and the listener on
     * 127.0.0.1:9093 would never bind — turning on "Be a mailbox"
     * in the UI would silently leave Sprint 3's push-notify channel
     * unavailable.
     */
    @Inject lateinit var mailboxNotifyHost: MailboxNotifyHost

    override fun onCreate() {
        super.onCreate()
        // Touch mailboxNotifyHost so Kotlin doesn't elide the field
        // and so the singleton's init {} block has fired by the time
        // anything else runs. The reference itself is unused.
        @Suppress("UNUSED_VARIABLE")
        val warm = mailboxNotifyHost
        // Crypto + DB bring-up happens lazily via Hilt. We do NOT eagerly
        // open the database — it requires the hardware-keystore identity
        // key, which must not be unlocked until the user has at least
        // launched the app interactively. See SECURITY-MODEL.md §4.

        // Tor + ProfileHttpServer start was previously eager here, but
        // bootstrap burns 10-60s of CPU + battery on slow devices even
        // when the user is just opening Settings or About. Both are now
        // started by MainActivity inside the biometric-gate's unlocked
        // branch — see MainActivity.LaunchedEffect — so they only spin
        // up when the user is actually past Welcome. start() is
        // idempotent on both sides.
    }

    private companion object {
        private const val TAG = "WysprApplication"
    }
}
