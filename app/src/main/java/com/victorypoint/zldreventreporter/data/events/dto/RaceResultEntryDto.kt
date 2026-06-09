package com.victorypoint.zldreventreporter.data.events.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Root response from GET /api/race-results/entries — object wrapping the array.
@JsonClass(generateAdapter = true)
data class RaceResultsResponseDto(
    @Json(name = "entries") val entries: List<RaceResultEntryDto>? = null,
)

@JsonClass(generateAdapter = true)
data class RaceResultEntryDto(
    @Json(name = "profileId") val profileId: Long? = null,
    @Json(name = "rank") val rank: Int? = null,
    @Json(name = "lateJoin") val lateJoin: Boolean? = null,
    @Json(name = "profileData") val profileData: ProfileDataDto? = null,
    @Json(name = "activityData") val activityData: ActivityDataDto? = null,
)

@JsonClass(generateAdapter = true)
data class ProfileDataDto(
    @Json(name = "firstName") val firstName: String? = null,
    @Json(name = "lastName") val lastName: String? = null,
)

@JsonClass(generateAdapter = true)
data class ActivityDataDto(
    @Json(name = "durationInMilliseconds") val durationInMilliseconds: Long? = null,
    @Json(name = "segmentDistanceInMeters") val segmentDistanceInMeters: Int? = null,
)
