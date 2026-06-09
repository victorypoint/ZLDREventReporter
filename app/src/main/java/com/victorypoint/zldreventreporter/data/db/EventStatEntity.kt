package com.victorypoint.zldreventreporter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
@Entity(tableName = "event_stats")
data class EventStatEntity(
    @PrimaryKey val eventId: Long,
    val eventName: String,
    val eventDate: Long,       // epoch millis
    val sport: String,         // "CYCLING" or "RUNNING"
    val countSignedUp: Int,
    val countShowedUp: Int,
    val countCompleted: Int,
    val fetchedAt: Long,       // epoch millis, for cache invalidation
    val signedUpFrozen: Boolean = false, // dormant — kept to avoid a schema migration; always false for new events
    val durationInSeconds: Int = 3600,
)
