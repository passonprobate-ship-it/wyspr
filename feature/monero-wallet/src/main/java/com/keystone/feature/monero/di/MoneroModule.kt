package com.keystone.feature.monero.di

import com.keystone.feature.monero.MoneroCryptoEngine
import com.keystone.feature.monero.NotImplementedMoneroCryptoEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt graph for the Monero wallet module. Binds the
 * [MoneroCryptoEngine] interface to its stub for the v0.7.0a
 * slice. The eventual JNI-backed binding ships in v0.7.0b — at
 * that point this provider switches to return the real engine and
 * the rest of the module is unchanged.
 *
 * Note: [com.keystone.feature.monero.MoneroWalletService],
 * [com.keystone.feature.monero.MoneroKeyStore], and
 * [com.keystone.feature.monero.rpc.MoneroRpcClient] are
 * `@Singleton @Inject constructor()` classes, so Hilt builds them
 * automatically — only the engine needs an explicit binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object MoneroModule {

    @Provides
    @Singleton
    fun provideMoneroCryptoEngine(): MoneroCryptoEngine = NotImplementedMoneroCryptoEngine()
}
