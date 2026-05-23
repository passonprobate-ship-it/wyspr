package com.keystone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.keystone.core.database.entities.PeerPaymentAddressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerPaymentAddressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerPaymentAddressEntity)

    /**
     * Most-recently-added non-revoked address for [peerPub] on
     * [chain]. The send-XMR flow looks this up before constructing
     * a transfer.
     */
    @Query(
        "SELECT * FROM peer_payment_address " +
            "WHERE peer_pub = :peerPub AND chain = :chain AND revoked_at IS NULL " +
            "ORDER BY created_at DESC LIMIT 1"
    )
    suspend fun currentForPeer(peerPub: ByteArray, chain: String): PeerPaymentAddressEntity?

    /** Reactive variant — the UI subscribes to render "Send XMR" gating. */
    @Query(
        "SELECT * FROM peer_payment_address " +
            "WHERE peer_pub = :peerPub AND chain = :chain AND revoked_at IS NULL " +
            "ORDER BY created_at DESC LIMIT 1"
    )
    fun currentForPeerFlow(peerPub: ByteArray, chain: String): Flow<PeerPaymentAddressEntity?>

    /** Full address history for [peerPub] including revoked rows. Used by the audit/details UI. */
    @Query("SELECT * FROM peer_payment_address WHERE peer_pub = :peerPub ORDER BY created_at DESC")
    suspend fun allForPeer(peerPub: ByteArray): List<PeerPaymentAddressEntity>

    /** Mark a specific binding revoked without deleting it. */
    @Query(
        "UPDATE peer_payment_address SET revoked_at = :nowSeconds " +
            "WHERE peer_pub = :peerPub AND chain = :chain AND address = :address"
    )
    suspend fun revoke(peerPub: ByteArray, chain: String, address: String, nowSeconds: Long)

    @Query("DELETE FROM peer_payment_address WHERE peer_pub = :peerPub")
    suspend fun deleteForPeer(peerPub: ByteArray)

    @Query("DELETE FROM peer_payment_address")
    suspend fun deleteAll()
}
