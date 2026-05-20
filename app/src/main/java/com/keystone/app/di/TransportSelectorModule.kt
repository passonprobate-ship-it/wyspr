package com.keystone.app.di

import com.keystone.app.transport.TorHiddenServiceTransport
import com.keystone.app.transport.TransportSelector
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.transport.SyncTransportFacade
import com.keystone.core.transport.TorBackend
import com.keystone.core.transport.bluetooth.BleTransport
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
        database: KeystoneDatabase,
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
