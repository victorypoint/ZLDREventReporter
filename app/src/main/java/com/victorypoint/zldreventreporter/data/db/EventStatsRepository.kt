package com.victorypoint.zldreventreporter.data.db

import kotlinx.coroutines.flow.Flow

class EventStatsRepository(private val dao: EventStatDao) {

    fun getAllStats(): Flow<List<EventStatEntity>> = dao.getAllStats()

    fun getStatsForPeriod(from: Long, to: Long, sport: String): Flow<List<EventStatEntity>> =
        dao.getStatsForPeriod(from, to, sport)

    fun getCount(): Flow<Int> = dao.getCount()

    suspend fun upsert(stat: EventStatEntity) = dao.upsert(stat)

    suspend fun insertIfNew(stat: EventStatEntity) = dao.insertIfNew(stat)

    suspend fun updateEventDateIfMissing(eventId: Long, date: Long) =
        dao.updateEventDateIfMissing(eventId, date)

    suspend fun resetFetchedAtIfFuture(eventId: Long, nowMs: Long) =
        dao.resetFetchedAtIfFuture(eventId, nowMs)

    suspend fun updateSignedUpForDisplay(eventId: Long, count: Int) =
        dao.updateSignedUpForDisplay(eventId, count)

    suspend fun countAll(): Int = dao.countAll()
    suspend fun countPast(nowMs: Long): Int = dao.countPast(nowMs)
    suspend fun countPastUnfetched(nowMs: Long): Int = dao.countPastUnfetched(nowMs)

    suspend fun getPastWithoutResults(
        gracePeriodMs: Long,
        retryWindowMs: Long,
        retryThrottleMs: Long,
    ): List<EventStatEntity> = dao.getPastWithoutResults(gracePeriodMs, retryWindowMs, retryThrottleMs)

    suspend fun getNextUpcoming(): List<EventStatEntity> = dao.getNextUpcoming()

    suspend fun getInProgress(nowMs: Long, cutoffMs: Long): List<EventStatEntity> =
        dao.getInProgress(nowMs, cutoffMs)

    suspend fun getAllForExport(): List<EventStatEntity> = dao.getAllForExport()

    suspend fun getById(eventId: Long): EventStatEntity? = dao.getById(eventId)

    suspend fun resetFetchedAt(eventId: Long) = dao.resetFetchedAt(eventId)

    suspend fun updateCompleted(eventId: Long, count: Int) = dao.updateCompleted(eventId, count)

    suspend fun updateDuration(eventId: Long, duration: Int) = dao.updateDuration(eventId, duration)

    suspend fun deleteAll() = dao.deleteAll()
}
