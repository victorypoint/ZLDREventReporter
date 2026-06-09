package com.victorypoint.zldreventreporter.data.events

import com.victorypoint.zldreventreporter.data.events.dto.RaceResultsResponseDto
import com.victorypoint.zldreventreporter.data.events.dto.ZwiftEventDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface EventsApi {
    // Offset-based pagination via `start` (0, 200, 400, …). Use `tags` to filter by ZLDR tag.
    @GET("api/public/events/upcoming")
    suspend fun getUpcomingEvents(
        @Header("Zwift-Api-Version") apiVersion: String = "2.5",
        @Query("limit") limit: Int = 200,
        @Query("start") start: Int = 0,
        @Query("tags") tags: String? = null,
        @Query("eventStartsAfter") eventStartsAfter: String? = null,
        @Query("eventStartsBefore") eventStartsBefore: String? = null,
    ): List<ZwiftEventDto>

    // Non-public — returns totalSignedUpCount, totalJoinedCount. Used by Pass 1b and Pass 2.
    @GET("api/events/{eventId}")
    suspend fun getEventWithCounts(
        @Path("eventId") eventId: Long,
        @Header("Zwift-Api-Version") apiVersion: String = "2.5",
    ): ZwiftEventDto

    // Returns finished-rider entries per subgroup. Response is {"entries":[...]} wrapper.
    @GET("api/race-results/entries")
    suspend fun getRaceResultsEntries(
        @Query("event_subgroup_id") subgroupId: Long,
        @Header("Zwift-Api-Version") apiVersion: String = "2.5",
    ): RaceResultsResponseDto
}
