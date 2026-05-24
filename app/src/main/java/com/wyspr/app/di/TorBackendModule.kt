package com.wyspr.app.di

import android.content.Context
import com.wyspr.app.transport.EmbeddedTorBackend
import com.wyspr.app.transport.TorHiddenServiceTransport
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.transport.TorTransport
import com.wyspr.core.transport.Transport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the [TorBackend] interface + the matching transport.
 *
 * Today: [EmbeddedTorBackend] backed by kmp-tor — the .onion is
 * derived deterministically from the keystore identity so the address
 * is stable across reinstalls. [TorHiddenServiceTransport] is provided
 * alongside as a [com.wyspr.core.transport.Transport] singleton,
 * but Sprint 4 only stands it up — the handshake and sync pipelines
 * still go over BLE. Sprint 5 introduces a Transport-of-transports
 * selector that switches between BLE and Tor based on `peerOnion`
 * availability.
 */
@Module
@InstallIn(SingletonComponent::class)
object TorBackendModule {
    @Provides
    @Singleton
    fun provideTorBackend(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager,
    ): TorBackend = EmbeddedTorBackend(context, keystoreManager)

    @Provides
    @Singleton
    fun provideTorHiddenServiceTransport(
        torBackend: TorBackend,
    ): TorHiddenServiceTransport = TorHiddenServiceTransport(torBackend)

    @Provides
    @Singleton
    @TorTransport
    fun provideTorTransport(
        impl: TorHiddenServiceTransport,
    ): Transport = impl
}
