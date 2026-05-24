package com.wyspr.app.di

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.wyspr.app.biometric.BiometricCapability
import com.wyspr.app.biometric.BiometricUnlocker
import com.wyspr.app.KeyRotationService
import com.wyspr.core.crypto.AndroidKeystoreManager
import com.wyspr.core.crypto.KeystoreManager
import com.wyspr.core.crypto.KeystoreOptions
import com.wyspr.core.database.CommunityService
import com.wyspr.core.database.WysprDatabase
import com.wyspr.core.database.WysprDatabaseImpl
import com.wyspr.core.currency.WalletService
import com.wyspr.core.transport.TorBackend
import com.wyspr.core.ui.settings.BiometricSettings
import com.wyspr.core.ui.settings.KeyRotationSettings
import com.wyspr.core.trust.HandshakeProtocolImpl
import com.wyspr.core.trust.TrustGraphService
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
    fun provideWysprDatabase(
        @ApplicationContext context: Context,
        keystore: KeystoreManager,
    ): WysprDatabase = WysprDatabaseImpl(context, keystore)

    @Provides
    @Singleton
    fun provideCommunityService(database: WysprDatabase): CommunityService =
        CommunityService(database = database)

    @Provides
    @Singleton
    fun provideTrustGraphService(
        database: WysprDatabase,
        keystore: KeystoreManager,
    ): TrustGraphService = TrustGraphService(database = database, keystore = keystore)

    // Provided as the concrete impl (not the HandshakeProtocol interface)
    // for the v0.1 slice — the onboarding flow needs mintWithSecret which
    // lives on the impl. Switch to the interface once Noise XX is wired
    // and the impl no longer exposes minting internals.
    @Provides
    @Singleton
    fun provideHandshakeProtocol(
        keystore: KeystoreManager,
        sodium: LazySodiumAndroid,
        database: WysprDatabase,
        trustGraphService: TrustGraphService,
        torBackend: TorBackend,
    ): HandshakeProtocolImpl = HandshakeProtocolImpl(
        keystore = keystore,
        sodium = sodium,
        database = database,
        trustGraphService = trustGraphService,
        // Read the latest published .onion each time we mint a QR.
        // A nullable read is correct — Tor may still be bootstrapping
        // on the first few QRs after a fresh install, and the QR
        // shape allows null.
        localOnion = { torBackend.onionAddress.value },
    )

    @Provides
    @Singleton
    fun provideWalletService(
        keystore: KeystoreManager,
        database: WysprDatabase,
    ): WalletService = WalletService(keystore = keystore, database = database)

    @Provides
    @Singleton
    fun provideBiometricSettings(
        @ApplicationContext context: Context,
    ): BiometricSettings = BiometricSettings(context)

    @Provides
    @Singleton
    fun provideKeyRotationSettings(
        @ApplicationContext context: Context,
    ): KeyRotationSettings = KeyRotationSettings(context)

    @Provides
    @Singleton
    fun provideKeyRotationService(
        @ApplicationContext context: Context,
        keystore: KeystoreManager,
        database: WysprDatabase,
        sodium: LazySodiumAndroid,
        rotationSettings: KeyRotationSettings,
    ): KeyRotationService = KeyRotationService(
        context = context,
        keystore = keystore,
        database = database,
        sodium = sodium,
        rotationSettings = rotationSettings,
    )

    @Provides
    @Singleton
    fun provideMailboxSettings(
        @ApplicationContext context: Context,
    ): com.wyspr.core.ui.settings.MailboxSettings =
        com.wyspr.core.ui.settings.MailboxSettings(context)
}
