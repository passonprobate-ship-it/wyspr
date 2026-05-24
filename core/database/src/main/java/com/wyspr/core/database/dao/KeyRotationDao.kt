package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.KeyRotationEntity

@Dao
interface KeyRotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rotation: KeyRotationEntity)

    @Query("SELECT * FROM key_rotation")
    suspend fun all(): List<KeyRotationEntity>

    @Query("SELECT * FROM key_rotation WHERE oldPub = :oldPub")
    suspend fun byOldPub(oldPub: ByteArray): List<KeyRotationEntity>

    @Query("SELECT COUNT(*) FROM key_rotation WHERE oldPub = :oldPub")
    suspend fun countByOldPub(oldPub: ByteArray): Int
}
