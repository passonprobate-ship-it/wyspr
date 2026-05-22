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

        // Tor + ProfileHttpServer start was previously eager here, but
        // bootstrap burns 10-60s of CPU + battery on slow devices even
        // when the user is just opening Settings or About. Both are now
        // started by MainActivity inside the biometric-gate's unlocked
        // branch — see MainActivity.LaunchedEffect — so they only spin
        // up when the user is actually past Welcome. start() is
        // idempotent on both sides.
    }

    private companion object {
        private const val TAG = "KeystoneApplication"
    }
}
