package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE id = 0 LIMIT 1")
    suspend fun get(): UserProfileEntity?

    /**
     * Reactive feed — the HTTP server doesn't need this, but the
     * edit screen subscribes so a "save" reflects immediately
     * without round-tripping through a viewmodel reload.
     */
    @Query("SELECT * FROM user_profile WHERE id = 0 LIMIT 1")
    fun getFlow(): Flow<UserProfileEntity?>
}
