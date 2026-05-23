package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.AddressBookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AddressBookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AddressBookEntity)

    @Query(
        "SELECT * FROM address_book WHERE chain = :chain " +
            "ORDER BY last_used_at DESC, label ASC",
    )
    fun forChainFlow(chain: String): Flow<List<AddressBookEntity>>

    @Query(
        "SELECT * FROM address_book WHERE chain = :chain " +
            "ORDER BY last_used_at DESC, label ASC",
    )
    suspend fun forChain(chain: String): List<AddressBookEntity>

    @Query("UPDATE address_book SET last_used_at = :nowSeconds WHERE chain = :chain AND label = :label")
    suspend fun touch(chain: String, label: String, nowSeconds: Long)

    @Query("DELETE FROM address_book WHERE chain = :chain AND label = :label")
    suspend fun delete(chain: String, label: String)
}
