package com.keystone.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KeystoneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Crypto + DB bring-up happens lazily via Hilt. We do NOT eagerly
        // open the database — it requires the hardware-keystore identity
        // key, which must not be unlocked until the user has at least
        // launched the app interactively. See SECURITY-MODEL.md §4.
    }
}
