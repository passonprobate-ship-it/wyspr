package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.CurrencyEnvelopeEntity

@Dao
interface CurrencyEnvelopeDao {

    /**
     * Insert a new envelope. Throws (SQLiteConstraintException) on PK
     * collision — that's the signal for a possible double-spend. The
     * caller must then fetch the existing row via [get] and compare
     * `body` bytes; identical bodies are benign retransmissions,
     * different bodies are a slash candidate.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(envelope: CurrencyEnvelopeEntity)

    /**
     * Insert-or-replace. Use ONLY for envelopes the local device itself
     * produced and is updating (e.g. fixing observedAt). Never use this
     * for envelopes received from peers — it would silently overwrite
     * the evidence of a double-spend.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceLocal(envelope: CurrencyEnvelopeEntity)

    @Query(
        "SELECT * FROM currency_envelope WHERE community = :community " +
            "AND typeTag = :typeTag AND primaryKey = :primaryKey LIMIT 1"
    )
    suspend fun get(
        community: ByteArray,
        typeTag: Int,
        primaryKey: ByteArray,
    ): CurrencyEnvelopeEntity?

    @Query("SELECT * FROM currency_envelope WHERE community = :community AND typeTag = :typeTag")
    suspend fun byType(community: ByteArray, typeTag: Int): List<CurrencyEnvelopeEntity>

    @Query(
        "SELECT * FROM currency_envelope WHERE community = :community " +
            "AND typeTag = :typeTag ORDER BY observedAt ASC"
    )
    suspend fun byTypeOrdered(
        community: ByteArray,
        typeTag: Int,
    ): List<CurrencyEnvelopeEntity>

    @Query("SELECT * FROM currency_envelope WHERE community = :community")
    suspend fun forCommunity(community: ByteArray): List<CurrencyEnvelopeEntity>

    @Query("SELECT COUNT(*) FROM currency_envelope WHERE community = :community")
    suspend fun countForCommunity(community: ByteArray): Int

    @Query("SELECT COUNT(*) FROM currency_envelope")
    suspend fun count(): Int

    @Query("DELETE FROM currency_envelope")
    suspend fun deleteAll()
}
