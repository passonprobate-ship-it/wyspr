package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "coordination_event",
    indices = [
        Index(value = ["communityId", "startsAt"]),
        Index(value = ["creatorPub"]),
    ],
)
data class CoordinationEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: ByteArray,
    @ColumnInfo(name = "creatorPub") val creatorPub: ByteArray,
    @ColumnInfo(name = "communityId") val communityId: ByteArray,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "location") val location: String?,
    @ColumnInfo(name = "startsAt") val startsAt: Long,
    @ColumnInfo(name = "endsAt") val endsAt: Long?,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "signature") val signature: ByteArray,
)
