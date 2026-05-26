package com.wyspr.app.di

import android.content.Context
import com.wyspr.core.transport.bluetooth.BleTransport
import com.wyspr.core.transport.wifidirect.WifiDirectTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideBleTransport(@ApplicationContext context: Context): BleTransport =
        BleTransport(context)

    @Provides
    @Singleton
    fun provideWifiDirectTransport(@ApplicationContext context: Context): WifiDirectTransport =
        WifiDirectTransport(context)
}
