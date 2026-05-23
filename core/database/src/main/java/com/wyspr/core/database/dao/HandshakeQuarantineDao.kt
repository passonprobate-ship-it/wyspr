package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.HandshakeQuarantineEntity

@Dao
interface HandshakeQuarantineDao {

    /** Latest quarantine record for [peerPub], or null if never quarantined. */
    @Query("SELECT * FROM handshake_quarantine WHERE peer_pub = :peerPub")
    suspend fun forPeer(peerPub: ByteArray): HandshakeQuarantineEntity?

    /** Returns true iff [peerPub] is currently quarantined at or after [nowSeconds]. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM handshake_quarantine " +
            "WHERE peer_pub = :peerPub AND quarantined_until > :nowSeconds)",
    )
    suspend fun isQuarantined(peerPub: ByteArray, nowSeconds: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HandshakeQuarantineEntity)

    @Query("DELETE FROM handshake_quarantine WHERE quarantined_until <= :nowSeconds")
    suspend fun sweepExpired(nowSeconds: Long): Int
}
