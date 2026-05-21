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

    /**
     * The DB passphrase, kept alive for the lifetime of the open Room
     * database. `SupportOpenHelperFactory` stores a reference (not a
     * copy) of this array and consults it again every time the
     * connection pool spins up a non-primary connection — zeroing it
     * after the primary connection opens (as earlier revisions did)
     * left the pool unable to decrypt on the next read and crashed
     * any DAO that opened a secondary connection with SQLCipher's
     * "file is not a database" error. Zeroed in [close]/[wipe].
     */
    @Volatile
    private var passphrase: ByteArray? = null
    private val openLock = Mutex()

    override val isOpen: Boolean get() = room?.isOpen == true

    override val trustEdgeDao get() = requireOpen().trustEdgeDao()
    override val revocationDao get() = requireOpen().revocationDao()
    override val accountDao get() = requireOpen().accountDao()
    override val currencyEnvelopeDao get() = requireOpen().currencyEnvelopeDao()
    override val communityMembershipDao get() = requireOpen().communityMembershipDao()
    override val messageDao get() = requireOpen().messageDao()
    override val contactDao get() = requireOpen().contactDao()
    override val groupDao get() = requireOpen().groupDao()
    override val groupMemberDao get() = requireOpen().groupMemberDao()
    override val groupMessageDao get() = requireOpen().groupMessageDao()
    override val userProfileDao get() = requireOpen().userProfileDao()
    override val mailboxBindingDao get() = requireOpen().mailboxBindingDao()
    override val mailboxStoredDao get() = requireOpen().mailboxStoredDao()
    override val mailboxPullCursorDao get() = requireOpen().mailboxPullCursorDao()

    override suspend fun open() = openLock.withLock {
        withContext(Dispatchers.IO) {
            if (room?.isOpen == true) return@withContext
            // Load the native sqlcipher library before constructing any helpers.
            // System.loadLibrary is idempotent per classloader.
            System.loadLibrary("sqlcipher")
            val pp = keystore.deriveSubkey(DB_KEY_INFO, length = 32)
            // Stash the passphrase reference; the factory holds it for
            // the lifetime of every connection it spins up. See the
            // [passphrase] field comment for why we can't zero it here.
            passphrase = pp
            val factory = SupportOpenHelperFactory(pp)
            val built = Room.databaseBuilder(
                appContext,
                KeystoneRoomDatabase::class.java,
                DB_FILENAME,
            )
                .openHelperFactory(factory)
                // Real migrations from v5 onwards so the first
                // hardware-proven pair (2026-05-20) survives schema
                // bumps. Earlier versions still fall back to a
                // destructive migration since no real install was
                // ever on those.
                .addMigrations(
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                )
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                .build()
            room = built
            // Touch the DB to force open + key check now, not on first
            // DAO call. If the passphrase is wrong (e.g. keystore was
            // rotated externally), this throws here, not silently later.
            built.openHelper.writableDatabase
        }
    }

    override suspend fun close() = openLock.withLock {
        withContext(Dispatchers.IO) {
            room?.close()
            room = null
            passphrase?.fill(0)
            passphrase = null
        }
    }

    override suspend fun wipe() {
        openLock.withLock {
            withContext(Dispatchers.IO) {
                room?.close()
                room = null
                passphrase?.fill(0)
                passphrase = null
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
