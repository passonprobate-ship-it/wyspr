package com.keystone.feature.monero.di

import android.content.Context
import com.keystone.core.transport.TorBackend
import com.keystone.feature.monero.MoneroKeyStore
import com.keystone.feature.monero.network.TorSocksOkHttp
import com.keystone.feature.monero.persistence.EncryptedWalletDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Hilt graph for the Monero wallet module.
 *
 * Sprint W1 swap-out: the v0.7.0a scaffolding bound a stubbed
 * [com.keystone.feature.monero.MoneroCryptoEngine] in anticipation
 * of a future Monerujo JNI integration. mollyim's
 * `im.molly:monero-wallet-sdk` 1.0.0 makes that engine redundant —
 * it ships native libwallet2 in a sandboxed Android process and
 * exposes a Kotlin Flow API directly. This module now provides the
 * Keystone-specific glue around mollyim: keystore-backed at-rest
 * encryption and a Tor-routed OkHttp client.
 *
 * [com.keystone.feature.monero.MoneroWalletService] and
 * [com.keystone.feature.monero.MoneroKeyStore] are
 * `@Singleton @Inject constructor()` classes; Hilt builds them
 * automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object MoneroModule {

    /**
     * The OkHttp client mollyim's [im.molly.monero.sdk.MoneroNodeClient]
     * uses for daemon-RPC over Tor. Every byte of wallet traffic —
     * descriptor fetches, block syncs, transaction broadcasts —
     * routes through the embedded Tor SOCKS proxy advertised by
     * [TorBackend.socksPort]. The wallet engine never knows about
     * direct internet.
     */
    @Provides
    @Singleton
    fun provideWalletHttpClient(torBackend: TorBackend): OkHttpClient =
        TorSocksOkHttp.build(torBackend)

    /**
     * Encrypted file-backed wallet data store. Wallet seed + state
     * persists to app-private storage, wrapped with the keystore-
     * derived subkey from [MoneroKeyStore]. Wiping the device
     * identity renders the on-disk wallet bytes unrecoverable.
     */
    @Provides
    @Singleton
    fun provideWalletDataStore(
        @ApplicationContext context: Context,
        keyStore: MoneroKeyStore,
    ): EncryptedWalletDataStore = EncryptedWalletDataStore(context, keyStore)
}
