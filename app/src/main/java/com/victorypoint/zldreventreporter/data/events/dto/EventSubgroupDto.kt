package com.victorypoint.zldreventreporter.data.events.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EventSubgroupDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "label") val label: Int? = null,
    @Json(name = "subgroupLabel") val subgroupLabel: String? = null,
    @Json(name = "distanceInMeters") val distanceInMeters: Double? = null,
    @Json(name = "laps") val laps: Int? = null,
    @Json(name = "durationInSeconds") val durationInSeconds: Int? = null,
    @Json(name = "totalEntrantCount") val totalEntrantCount: Int? = null,
    @Json(name = "signedUpCount") val signedUpCount: Int? = null,
    @Json(name = "eventSubgroupStart") val eventSubgroupStart: String? = null,
    @Json(name = "rulesId") val rulesId: Long? = null,
)
