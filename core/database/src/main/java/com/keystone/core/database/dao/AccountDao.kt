package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.keystone.core.database.entities.AccountEntity

@Dao
interface AccountDao {

    /** Atomic insert-or-replace. Used by the ledger reducer. */
    @Upsert
    suspend fun upsert(account: AccountEntity)

    /** Insert that fails if the row already exists — for first-sight creation. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNew(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM account WHERE pub = :pub")
    suspend fun get(pub: ByteArray): AccountEntity?

    @Query("SELECT * FROM account")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    @Query("UPDATE account SET slashed = 1, balanceCached = 0 WHERE pub = :pub")
    suspend fun markSlashed(pub: ByteArray)

    @Query("DELETE FROM account")
    suspend fun deleteAll()
}
