package com.victorypoint.zldreventreporter.data.events

import android.util.Log
import com.victorypoint.zldreventreporter.data.SyncMetadataStore
import com.victorypoint.zldreventreporter.data.db.EventStatEntity
import retrofit2.HttpException
import com.victorypoint.zldreventreporter.data.db.EventStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Zwift API returns "+0000" (RFC 822, no colon); Instant.parse() requires ISO-8601 "+00:00" or "Z".
private val ZWIFT_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]X")

private fun parseEventStart(text: String): Long =
    runCatching { Instant.parse(text).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(text, ZWIFT_DATE_FMT).toInstant().toEpochMilli() }
        .getOrDefault(0L)

private const val TAG = "EventSyncRepository"
private const val RATE_LIMIT_DELAY_MS   = 1_500L
private const val GRACE_PERIOD_MS       = 30 * 60 * 1000L
private const val RETRY_WINDOW_MS       = 48 * 60 * 60 * 1000L
private const val RETRY_THROTTLE_MS     =  5 * 60 * 1000L
private const val NAME_SCAN_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val IN_PROGRESS_WINDOW_MS =  3 * 60 * 60 * 1000L

class EventSyncRepository(
    private val eventsApi: EventsApi,
    private val statsRepository: EventStatsRepository,
    private val syncMetadata: SyncMetadataStore,
) {
    fun syncNow(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Loading(0, 0))

        val now = Instant.now()
        val nowMs = now.toEpochMilli()

        // Pass 1: discover upcoming ZLDR events via tag filter + name-prefix fallback scan.
        // The tag filter is the primary source; the name scan catches events missing the tag.
        // Sign-up counts are not fetched here — PreEventSyncWorker captures them ~5 min before
        // each event starts using the authenticated /api/events/{id} endpoint.
        val allKnown = withContext(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    val zldrDeferred      = async { eventsApi.getUpcomingEvents(tags = "zldr") }
                    val zldrIdersDeferred = async { eventsApi.getUpcomingEvents(tags = "zldriders") }
                    val nameMatchDeferred = async {
                        val sinceLastScan = nowMs - syncMetadata.lastNameScanAt
                        if (sinceLastScan < NAME_SCAN_INTERVAL_MS) {
                            Log.d(TAG, "Name-prefix scan skipped (last ran ${sinceLastScan / 3_600_000}h ago)")
                            emptyList()
                        } else {
                            val result = buildList {
                                var start = 0
                                var pageSize: Int
                                do {
                                    val page = eventsApi.getUpcomingEvents(start = start)
                                    addAll(page.filter {
                                        it.name?.startsWith("ZLDR", ignoreCase = true) == true
                                    })
                                    pageSize = page.size
                                    start += 200
                                } while (pageSize == 200)
                            }
                            syncMetadata.lastNameScanAt = nowMs
                            result
                        }
                    }
                    val zldr        = zldrDeferred.await()
                    val zldrIders   = zldrIdersDeferred.await()
                    val nameMatches = nameMatchDeferred.await()
                    val taggedIds   = (zldr + zldrIders).map { it.id }.toHashSet()
                    val extras      = nameMatches.filter { it.id !in taggedIds }
                    if (extras.isNotEmpty()) {
                        Log.w(TAG, "Name-prefix scan found ${extras.size} untagged ZLDR event(s): " +
                                extras.joinToString { "'${it.name}' (${it.id})" })
                    }
                    val seen = mutableSetOf<Long>()
                    (zldr + zldrIders + nameMatches)
                        .filter { seen.add(it.id) }
                        .sortedBy { it.eventStart }
                }
            }
        }.getOrElse {
            Log.e(TAG, "Event discovery failed: ${it.message}", it)
            emit(SyncProgress.Error(it.message ?: "Failed to discover events"))
            return@flow
        }

        withContext(Dispatchers.IO) {
            allKnown.forEach { dto ->
                val eventDate = dto.eventStart?.let { parseEventStart(it) } ?: 0L
                val sport = if (dto.sport?.uppercase() == "RUNNING") "RUNNING" else "CYCLING"

                statsRepository.insertIfNew(
                    EventStatEntity(
                        eventId           = dto.id,
                        eventName         = dto.name ?: "",
                        eventDate         = eventDate,
                        sport             = sport,
                        countSignedUp     = 0,
                        countShowedUp     = 0,
                        countCompleted    = 0,
                        fetchedAt         = 0L,
                        durationInSeconds = dto.durationInSeconds?.takeIf { it > 0 } ?: 3600,
                    )
                )
                if (eventDate > 0L) {
                    statsRepository.updateEventDateIfMissing(dto.id, eventDate)
                    statsRepository.resetFetchedAtIfFuture(dto.id, nowMs)
                }
                val duration = dto.durationInSeconds?.takeIf { it > 0 }
                if (duration != null) {
                    statsRepository.updateDuration(dto.id, duration)
                }
            }
        }

        // Pass 1b: fetch live sign-up counts for all upcoming events concurrently.
        // Uses the authenticated endpoint (public API returns 0 for totalSignedUpCount).
        val upcomingDtos = allKnown.filter { dto ->
            (dto.eventStart?.let { parseEventStart(it) } ?: 0L) > nowMs
        }
        coroutineScope {
            upcomingDtos.map { dto ->
                async(Dispatchers.IO) {
                    val detail = runCatching { eventsApi.getEventWithCounts(dto.id) }.getOrNull()
                    val count = detail?.totalSignedUpCount ?: detail?.totalEntrantCount ?: 0
                    statsRepository.updateSignedUpForDisplay(dto.id, count)
                }
            }.awaitAll()
        }
        Log.d(TAG, "Pass 1b: updated sign-up counts for ${upcomingDtos.size} upcoming events (concurrent)")

        val dbTotal         = withContext(Dispatchers.IO) { statsRepository.countAll() }
        val dbPast          = withContext(Dispatchers.IO) { statsRepository.countPast(nowMs) }
        val dbPastUnfetched = withContext(Dispatchers.IO) { statsRepository.countPastUnfetched(nowMs) }
        Log.d(TAG, "Pass 1: Zwift API tags+scan=${allKnown.size}; DB: total=$dbTotal past=$dbPast pastUnfetched=$dbPastUnfetched")

        // Pass 2: fetch counts for past events via /api/events/{id}.
        val toFetch = withContext(Dispatchers.IO) {
            statsRepository.getPastWithoutResults(
                gracePeriodMs   = nowMs - GRACE_PERIOD_MS,
                retryWindowMs   = nowMs - RETRY_WINDOW_MS,
                retryThrottleMs = nowMs - RETRY_THROTTLE_MS,
            )
        }
        Log.d(TAG, "Pass 2: fetching results for ${toFetch.size} completed events")

        if (toFetch.isEmpty()) {
            val fmt  = DateTimeFormatter.ofPattern("MMM d h:mm a z").withZone(ZoneId.systemDefault())
            val next = withContext(Dispatchers.IO) { statsRepository.getNextUpcoming() }
            next.forEach { e ->
                Log.d(TAG, "  next: '${e.eventName}' at ${fmt.format(Instant.ofEpochMilli(e.eventDate))}")
            }
        }
        emit(SyncProgress.Loading(0, toFetch.size))

        var newRecords = 0
        toFetch.forEachIndexed { index, existing ->
            delay(RATE_LIMIT_DELAY_MS)

            val eventDetail = withContext(Dispatchers.IO) {
                runCatching { eventsApi.getEventWithCounts(existing.eventId) }.getOrNull()
            }

            if (eventDetail == null) {
                Log.w(TAG, "  Skip ${existing.eventId} '${existing.eventName}': getEventWithCounts failed")
                emit(SyncProgress.Loading(index + 1, toFetch.size))
                return@forEachIndexed
            }

            val countSignedUp = (eventDetail.totalSignedUpCount ?: 0)
                .takeIf { it > 0 } ?: eventDetail.totalEntrantCount ?: 0
            val countShowedUp = eventDetail.totalJoinedCount ?: 0

            var countCompleted = 0
            val subgroups = eventDetail.eventSubgroups.orEmpty()
            if (subgroups.isEmpty()) {
                countCompleted = countShowedUp
                Log.d(TAG, "    no subgroups — completed = showedUp = $countShowedUp")
            } else {
                var anySucceeded = false
                for (sg in subgroups) {
                    delay(RATE_LIMIT_DELAY_MS)
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { eventsApi.getRaceResultsEntries(sg.id) }
                    }
                    val response = outcome.getOrNull()
                    if (response != null) {
                        anySucceeded = true
                        val count = response.entries?.size ?: 0
                        countCompleted += count
                        Log.d(TAG, "    sg ${sg.id} '${sg.subgroupLabel ?: sg.label}': $count finished")
                    } else {
                        val httpCode = (outcome.exceptionOrNull() as? HttpException)?.code()
                        Log.d(TAG, "    sg ${sg.id} '${sg.subgroupLabel ?: sg.label}': failed " +
                            "(${if (httpCode != null) "HTTP $httpCode" else outcome.exceptionOrNull()?.message})")
                    }
                }
                if (!anySucceeded && countShowedUp > 0) {
                    countCompleted = countShowedUp
                    Log.d(TAG, "    no race-results data — completed = showedUp = $countShowedUp")
                }
            }

            Log.d(TAG, "  ${existing.eventId} '${existing.eventName}': " +
                    "signedUp=$countSignedUp showedUp=$countShowedUp completed=$countCompleted | " +
                    "totalSignedUpCount=${eventDetail.totalSignedUpCount} " +
                    "totalJoinedCount=${eventDetail.totalJoinedCount} " +
                    "totalEntrantCount=${eventDetail.totalEntrantCount} " +
                    "eventType=${eventDetail.eventType} " +
                    "subgroupCount=${eventDetail.eventSubgroups?.size}")

            withContext(Dispatchers.IO) {
                statsRepository.upsert(
                    existing.copy(
                        countSignedUp  = maxOf(countSignedUp,  existing.countSignedUp),
                        countShowedUp  = maxOf(countShowedUp,  existing.countShowedUp),
                        countCompleted = maxOf(countCompleted, existing.countCompleted),
                        fetchedAt      = System.currentTimeMillis(),
                    )
                )
            }
            newRecords++
            emit(SyncProgress.Loading(index + 1, toFetch.size))
        }

        syncMetadata.lastSyncAt = System.currentTimeMillis()
        emit(SyncProgress.Success(newRecords))
    }

    suspend fun refreshInProgressCounts(): Int {
        val nowMs = Instant.now().toEpochMilli()
        val inProgress = statsRepository.getInProgress(nowMs, nowMs - IN_PROGRESS_WINDOW_MS)
        if (inProgress.isEmpty()) return 0
        coroutineScope {
            inProgress.map { entity ->
                async(Dispatchers.IO) {
                    val detail = runCatching { eventsApi.getEventWithCounts(entity.eventId) }.getOrNull()
                    val count = detail?.totalSignedUpCount?.takeIf { it > 0 }
                        ?: detail?.totalEntrantCount ?: 0
                    if (count > 0) statsRepository.updateSignedUpForDisplay(entity.eventId, count)
                }
            }.awaitAll()
        }
        Log.d(TAG, "In-progress refresh: updated sign-up counts for ${inProgress.size} event(s)")
        return inProgress.size
    }

    suspend fun anyReadyForPass2(): Boolean {
        val nowMs = Instant.now().toEpochMilli()
        return statsRepository.getInProgress(nowMs, nowMs - IN_PROGRESS_WINDOW_MS)
            .any { it.eventDate + GRACE_PERIOD_MS < nowMs }
    }
}
