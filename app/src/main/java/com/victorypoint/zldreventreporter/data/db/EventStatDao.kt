package com.victorypoint.zldreventreporter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventStatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: EventStatEntity)

    // Stores a placeholder without overwriting an existing record.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(stat: EventStatEntity)

    // Returns events eligible for a result fetch:
    //   - never fetched (fetchedAt = 0), OR
    //   - fetched but countCompleted is still 0 despite non-zero showedUp (retry within 48h,
    //     throttled to once per 5 minutes) — covers events fetched before subgroup results
    //     were implemented, and events whose result endpoint was temporarily empty.
    // gracePeriodMs   = now - 30m → only try events that started 30+ minutes ago
    // retryWindowMs   = now - 48h → only retry within 48 hours of the event
    // retryThrottleMs = now - 5m  → don't retry more than once per 5 minutes
    @Query("""
        SELECT * FROM event_stats
        WHERE eventDate < :gracePeriodMs
        AND (
            fetchedAt = 0
            OR (countCompleted = 0 AND countShowedUp > 0 AND eventDate > :retryWindowMs AND fetchedAt < :retryThrottleMs)
        )
        ORDER BY eventDate ASC
    """)
    suspend fun getPastWithoutResults(
        gracePeriodMs: Long,
        retryWindowMs: Long,
        retryThrottleMs: Long,
    ): List<EventStatEntity>

    @Query("SELECT * FROM event_stats ORDER BY eventDate DESC")
    fun getAllStats(): Flow<List<EventStatEntity>>

    @Query("SELECT * FROM event_stats WHERE fetchedAt = 0 ORDER BY eventDate ASC LIMIT 5")
    suspend fun getNextUpcoming(): List<EventStatEntity>

    @Query("SELECT * FROM event_stats WHERE eventDate <= :nowMs AND eventDate > :cutoffMs AND fetchedAt = 0")
    suspend fun getInProgress(nowMs: Long, cutoffMs: Long): List<EventStatEntity>

    // Correct a previously failed date parse (stored as 0).
    @Query("UPDATE event_stats SET eventDate = :date, fetchedAt = 0 WHERE eventId = :eventId AND eventDate = 0")
    suspend fun updateEventDateIfMissing(eventId: Long, date: Long)

    // Reset fetchedAt for an event that is still in the future — it cannot have real results yet.
    @Query("UPDATE event_stats SET fetchedAt = 0 WHERE eventId = :eventId AND eventDate > :nowMs AND fetchedAt > 0")
    suspend fun resetFetchedAtIfFuture(eventId: Long, nowMs: Long)

    // Update the live sign-up count for upcoming events from the authenticated API.
    @Query("UPDATE event_stats SET countSignedUp = :count WHERE eventId = :eventId AND fetchedAt = 0")
    suspend fun updateSignedUpForDisplay(eventId: Long, count: Int)

    @Query("SELECT COUNT(*) FROM event_stats")
    fun getCount(): Flow<Int>

    @Query("SELECT * FROM event_stats WHERE eventDate BETWEEN :from AND :to AND (:sport = 'ALL' OR sport = :sport) ORDER BY eventDate ASC")
    fun getStatsForPeriod(from: Long, to: Long, sport: String): Flow<List<EventStatEntity>>

    @Query("SELECT COUNT(*) FROM event_stats")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM event_stats WHERE eventDate < :nowMs")
    suspend fun countPast(nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM event_stats WHERE eventDate < :nowMs AND fetchedAt = 0")
    suspend fun countPastUnfetched(nowMs: Long): Int

    @Query("SELECT * FROM event_stats ORDER BY eventDate ASC")
    suspend fun getAllForExport(): List<EventStatEntity>

    @Query("UPDATE event_stats SET countCompleted = :count WHERE eventId = :eventId")
    suspend fun updateCompleted(eventId: Long, count: Int)

    @Query("SELECT * FROM event_stats WHERE eventId = :eventId LIMIT 1")
    suspend fun getById(eventId: Long): EventStatEntity?

    @Query("UPDATE event_stats SET fetchedAt = 0 WHERE eventId = :eventId")
    suspend fun resetFetchedAt(eventId: Long)

    @Query("UPDATE event_stats SET durationInSeconds = :duration WHERE eventId = :eventId")
    suspend fun updateDuration(eventId: Long, duration: Int)

    @Query("DELETE FROM event_stats")
    suspend fun deleteAll()
}
