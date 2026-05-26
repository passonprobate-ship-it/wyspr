package com.wyspr.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "coordination_rsvp",
    primaryKeys = ["eventId", "responderPub"],
    indices = [Index(value = ["eventId"])],
)
data class CoordinationRsvpEntity(
    @ColumnInfo(name = "eventId") val eventId: ByteArray,
    @ColumnInfo(name = "responderPub") val responderPub: ByteArray,
    @ColumnInfo(name = "communityId") val communityId: ByteArray,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "signature") val signature: ByteArray,
)
