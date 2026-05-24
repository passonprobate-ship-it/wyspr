package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.PeerSubAddressMintEntity

@Dao
interface PeerSubAddressMintDao {

    /**
     * Read the mint record for a paired peer on a given chain.
     * Returns null if we've never minted one — the wallet service
     * then mints + writes via [upsert].
     */
    @Query(
        "SELECT * FROM peer_subaddress_mint " +
            "WHERE peer_pub = :peerPub AND chain = :chain LIMIT 1",
    )
    suspend fun forPeer(peerPub: ByteArray, chain: String): PeerSubAddressMintEntity?

    /** All mints for an audit/debug view. */
    @Query("SELECT * FROM peer_subaddress_mint ORDER BY created_at DESC")
    suspend fun all(): List<PeerSubAddressMintEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerSubAddressMintEntity)

    @Query(
        "SELECT * FROM peer_subaddress_mint " +
            "WHERE account_index = :accountIndex AND sub_address_index = :subAddressIndex " +
            "AND chain = :chain LIMIT 1",
    )
    suspend fun forSubAddress(
        accountIndex: Int,
        subAddressIndex: Int,
        chain: String = "monero",
    ): PeerSubAddressMintEntity?
}
