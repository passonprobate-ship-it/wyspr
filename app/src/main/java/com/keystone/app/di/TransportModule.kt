package com.keystone.app.di

import android.content.Context
import com.keystone.core.transport.bluetooth.BleTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    /**
     * Single BLE transport for the whole app. Holds OS callbacks while
     * active, so we keep one instance and let UI layers start/stop it.
     */
    @Provides
    @Singleton
    fun provideBleTransport(@ApplicationContext context: Context): BleTransport =
        BleTransport(context)
}
