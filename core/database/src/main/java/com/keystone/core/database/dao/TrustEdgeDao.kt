package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.RevocationEntity
import com.keystone.core.database.entities.TrustEdgeEntity

@Dao
interface TrustEdgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edge: TrustEdgeEntity)

    @Query("SELECT * FROM trust_edge")
    suspend fun all(): List<TrustEdgeEntity>

    @Query("DELETE FROM trust_edge WHERE fromPub = :from AND toPub = :to")
    suspend fun delete(from: ByteArray, to: ByteArray)

    @Query("SELECT COUNT(*) FROM trust_edge")
    suspend fun count(): Int
}

@Dao
interface RevocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(revocation: RevocationEntity)

    @Query("SELECT * FROM revocation")
    suspend fun all(): List<RevocationEntity>

    @Query("SELECT * FROM revocation WHERE targetPub = :target")
    suspend fun byTarget(target: ByteArray): List<RevocationEntity>
}
