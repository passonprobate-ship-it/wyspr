package com.keystone.app.di

import android.content.Context
import com.keystone.app.transport.EmbeddedTorBackend
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.transport.TorBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the [TorBackend] interface to the build's chosen implementation.
 * Today: [EmbeddedTorBackend] backed by kmp-tor; the .onion is derived
 * deterministically from the keystore identity so the address is stable
 * across reinstalls.
 *
 * Sprint 4 will swap this for a TorBackend that also exposes the
 * negotiated SOCKS port + hidden-service target so the
 * `TorHiddenServiceTransport: Transport` impl can plug into the sync
 * engine.
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
}
