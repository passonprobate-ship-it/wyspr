package com.keystone.core.database

import android.content.Context
import androidx.room.Room
import com.keystone.core.crypto.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Default [KeystoneDatabase] implementation.
 *
 * The 256-bit database key is derived via HKDF from the hardware-keystore
 * identity seed. Two consequences fall out of that:
 *
 *  - The encrypted DB file cannot be opened on another device, even if
 *    pulled off disk — the keystore key it depends on isn't exportable.
 *  - Uninstalling the app destroys the keystore alias and the DB becomes
 *    unrecoverable garbage. This is the desired property; see
 *    SECURITY-MODEL.md §4.
 */
class KeystoneDatabaseImpl(
    private val appContext: Context,
    private val keystore: KeystoreManager,
) : KeystoneDatabase {

    @Volatile
    private var room: KeystoneRoomDatabase? = null
    private val openLock = Mutex()

    override val isOpen: Boolean get() = room?.isOpen == true

    override val trustEdgeDao get() = requireOpen().trustEdgeDao()
    override val revocationDao get() = requireOpen().revocationDao()
    override val accountDao get() = requireOpen().accountDao()
    override val currencyEnvelopeDao get() = requireOpen().currencyEnvelopeDao()
    override val communityMembershipDao get() = requireOpen().communityMembershipDao()
    override val messageDao get() = requireOpen().messageDao()

    override suspend fun open() = openLock.withLock {
        withContext(Dispatchers.IO) {
            if (room?.isOpen == true) return@withContext
            // Load the native sqlcipher library before constructing any helpers.
            // System.loadLibrary is idempotent per classloader.
            System.loadLibrary("sqlcipher")
            val passphrase = keystore.deriveSubkey(DB_KEY_INFO, length = 32)
            try {
                val factory = SupportOpenHelperFactory(passphrase)
                room = Room.databaseBuilder(
                    appContext,
                    KeystoneRoomDatabase::class.java,
                    DB_FILENAME,
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()
                // Touch the DB to force open + key check now, not on first
                // DAO call. If the passphrase is wrong (e.g. keystore was
                // rotated externally), this throws here, not silently later.
                room?.openHelper?.writableDatabase
            } finally {
                passphrase.fill(0)
            }
        }
    }

    override suspend fun close() = openLock.withLock {
        withContext(Dispatchers.IO) {
            room?.close()
            room = null
        }
    }

    override suspend fun wipe() {
        openLock.withLock {
            withContext(Dispatchers.IO) {
                room?.close()
                room = null
                // Delete the SQLite file plus the WAL + SHM sidecars Room may
                // leave next to it. deleteDatabase handles all three atomically.
                appContext.deleteDatabase(DB_FILENAME)
            }
        }
    }

    private fun requireOpen(): KeystoneRoomDatabase =
        room ?: error("KeystoneDatabase not open. Call open() first.")

    private companion object {
        const val DB_FILENAME = "keystone.db"
        val DB_KEY_INFO = "KEYSTONE/v1/db".encodeToByteArray()
    }
}
