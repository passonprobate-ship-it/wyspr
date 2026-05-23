package com.wyspr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wyspr.core.database.entities.GroupMessageDeliveryEntity

@Dao
interface GroupMessageDeliveryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(delivery: GroupMessageDeliveryEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM group_message_delivery WHERE msg_id = :msgId AND peer_pub = :peerPub)")
    suspend fun isDelivered(msgId: ByteArray, peerPub: ByteArray): Boolean

    @Query("SELECT peer_pub FROM group_message_delivery WHERE msg_id = :msgId")
    suspend fun deliveredPeers(msgId: ByteArray): List<ByteArray>
}
