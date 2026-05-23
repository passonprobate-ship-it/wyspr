package com.wyspr.app.di

import com.wyspr.app.transport.TorHiddenServiceTransport
import com.wyspr.app.transport.TransportSelector
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.transport.SyncTransportFacade
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.transport.bluetooth.BleTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportSelectorModule {
    @Provides
    @Singleton
    fun provideTransportSelector(
        torTransport: TorHiddenServiceTransport,
        bleTransport: BleTransport,
        torBackend: TorBackend,
        database: WysprDatabase,
    ): TransportSelector = TransportSelector(torTransport, bleTransport, torBackend, database)

    /**
     * `feature:messaging` (and future `feature:marketplace` sync) inject
     * the [SyncTransportFacade] abstraction. Binding the concrete selector
     * here keeps a single instance and avoids pulling `:app` symbols
     * into the feature modules.
     */
    @Provides
    @Singleton
    fun provideSyncTransportFacade(selector: TransportSelector): SyncTransportFacade = selector
}
