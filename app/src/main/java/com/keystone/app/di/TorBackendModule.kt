package com.keystone.app.di

import com.keystone.core.transport.TorBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the [TorBackend] interface to the build's chosen
 * implementation. Today: [TorBackend.Stub] (always Unavailable).
 *
 * The v0.6.1 sprint swaps this provider for an `EmbeddedTorBackend`
 * backed by kmp-tor. No call site changes — the rest of the app
 * keeps consuming the same interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object TorBackendModule {
    @Provides
    @Singleton
    fun provideTorBackend(): TorBackend = TorBackend.Stub()
}
