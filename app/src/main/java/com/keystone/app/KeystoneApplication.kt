package com.keystone.app

import android.app.Application
import android.util.Log
import com.keystone.app.profile.ProfileHttpServer
import com.keystone.core.transport.TorBackend
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KeystoneApplication : Application() {

    @Inject lateinit var torBackend: TorBackend
    @Inject lateinit var profileHttpServer: ProfileHttpServer

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Crypto + DB bring-up happens lazily via Hilt. We do NOT eagerly
        // open the database — it requires the hardware-keystore identity
        // key, which must not be unlocked until the user has at least
        // launched the app interactively. See SECURITY-MODEL.md §4.

        // Tor, however, is started eagerly: bootstrap takes 10-60s on a
        // good network and we want it ready by the time the user finishes
        // onboarding or opens the Community screen. The keystore-derive
        // inside torBackend.start() reads only the seed file (no
        // biometric prompt needed because the wrapping key is unbound
        // until the user enables biometric lock), so this does not
        // surface any UI.
        appScope.launch {
            try {
                torBackend.start()
            } catch (t: Throwable) {
                Log.w(TAG, "torBackend.start() threw", t)
            }
        }
        // The personal-page HTTP server binds on loopback only; Tor
        // forwards `<onion>:80` to it. Start eagerly — no key access
        // here, just a TCP listener. The page renderer reads the DB
        // lazily on each request and serves a placeholder when the
        // DB hasn't been unlocked yet.
        appScope.launch {
            try {
                profileHttpServer.start()
            } catch (t: Throwable) {
                Log.w(TAG, "profileHttpServer.start() threw", t)
            }
        }
    }

    private companion object {
        private const val TAG = "KeystoneApplication"
    }
}
