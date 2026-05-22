package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.SeenCertNonceEntity

@Dao
interface SeenCertNonceDao {

    /** Returns true iff [nonce] has already been observed. */
    @Query("SELECT EXISTS(SELECT 1 FROM seen_cert_nonce WHERE nonce = :nonce)")
    suspend fun isSeen(nonce: ByteArray): Boolean

    /** Insert-or-ignore. Caller checks affected rows ≥ 1 to detect a first-sight. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(entity: SeenCertNonceEntity): Long

    /** Garbage-collect entries older than the cert validity window. */
    @Query("DELETE FROM seen_cert_nonce WHERE observed_at < :cutoffSeconds")
    suspend fun sweepBefore(cutoffSeconds: Long): Int
}
