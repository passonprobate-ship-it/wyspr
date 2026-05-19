package com.keystone.app.di

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.keystone.app.biometric.BiometricCapability
import com.keystone.app.biometric.BiometricUnlocker
import com.keystone.core.crypto.AndroidKeystoreManager
import com.keystone.core.crypto.KeystoreManager
import com.keystone.core.crypto.KeystoreOptions
import com.keystone.core.database.CommunityService
import com.keystone.core.database.KeystoneDatabase
import com.keystone.core.database.KeystoneDatabaseImpl
import com.keystone.core.currency.WalletService
import com.keystone.core.ui.settings.BiometricSettings
import com.keystone.core.trust.HandshakeProtocolImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideSodium(): LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    @Provides
    @Singleton
    fun provideAndroidKeystoreManager(
        @ApplicationContext context: Context,
        sodium: LazySodiumAndroid,
        biometricSettings: BiometricSettings,
    ): AndroidKeystoreManager {
        // Read options *lazily* on each key-creation. After an identity
        // reset, the user's current preference takes effect on the next
        // identity even though the AndroidKeystoreManager singleton
        // outlives the reset. Capability is also re-checked so a user
        // who enrolled their biometric mid-session sees the change.
        val optionsProvider: () -> KeystoreOptions = {
            val capability = BiometricCapability.current(context)
            val bind = biometricSettings.bindToBiometric.value && capability.canAuthenticate
            KeystoreOptions(bindWrappingKeyToBiometric = bind)
        }
        return AndroidKeystoreManager(
            context = context,
            sodium = sodium,
            optionsProvider = optionsProvider,
        )
    }

    @Provides
    @Singleton
    fun provideKeystoreManager(impl: AndroidKeystoreManager): KeystoreManager = impl

    @Provides
    @Singleton
    fun provideBiometricUnlocker(): BiometricUnlocker = BiometricUnlocker()

    @Provides
    @Singleton
    fun provideKeystoneDatabase(
        @ApplicationContext context: Context,
        keystore: KeystoreManager,
    ): KeystoneDatabase = KeystoneDatabaseImpl(context, keystore)

    @Provides
    @Singleton
    fun provideCommunityService(database: KeystoneDatabase): CommunityService =
        CommunityService(database = database)

    // Provided as the concrete impl (not the HandshakeProtocol interface)
    // for the v0.1 slice — the onboarding flow needs mintWithSecret which
    // lives on the impl. Switch to the interface once Noise XX is wired
    // and the impl no longer exposes minting internals.
    @Provides
    @Singleton
    fun provideHandshakeProtocol(
        keystore: KeystoreManager,
        sodium: LazySodiumAndroid,
        database: KeystoneDatabase,
    ): HandshakeProtocolImpl = HandshakeProtocolImpl(
        keystore = keystore,
        sodium = sodium,
        database = database,
    )

    @Provides
    @Singleton
    fun provideWalletService(
        keystore: KeystoreManager,
        database: KeystoneDatabase,
    ): WalletService = WalletService(keystore = keystore, database = database)

    @Provides
    @Singleton
    fun provideBiometricSettings(
        @ApplicationContext context: Context,
    ): BiometricSettings = BiometricSettings(context)
}
