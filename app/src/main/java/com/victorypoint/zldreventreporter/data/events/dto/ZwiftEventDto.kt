package com.victorypoint.zldreventreporter.data.events.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ZwiftEventDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "shortDescription") val shortDescription: String? = null,
    @Json(name = "eventStart") val eventStart: String? = null,
    @Json(name = "durationInSeconds") val durationInSeconds: Int? = null,
    @Json(name = "distanceInMeters") val distanceInMeters: Double? = null,
    @Json(name = "laps") val laps: Int? = null,
    @Json(name = "sport") val sport: String? = null,
    @Json(name = "routeId") val routeId: Long? = null,
    @Json(name = "routeName") val routeName: String? = null,
    @Json(name = "imageUrl") val imageUrl: String? = null,
    @Json(name = "rulesId") val rulesId: Long? = null,
    @Json(name = "microserviceExternalResourceId") val microserviceExternalResourceId: String? = null,
    @Json(name = "microserviceEventVisibility") val microserviceEventVisibility: String? = null,
    @Json(name = "clubId") val clubId: Long? = null,
    @Json(name = "clubName") val clubName: String? = null,
    @Json(name = "organizer") val organizer: String? = null,
    @Json(name = "eventType") val eventType: String? = null,
    @Json(name = "visible") val visible: Boolean? = null,
    @Json(name = "eventSubgroups") val eventSubgroups: List<EventSubgroupDto>? = null,
    @Json(name = "totalEntrantCount") val totalEntrantCount: Int? = null,
    @Json(name = "totalSignedUpCount") val totalSignedUpCount: Int? = null,
    @Json(name = "totalJoinedCount") val totalJoinedCount: Int? = null,
    // Recurring-event fields — present on child instances of a repeating series.
    @Json(name = "recurring") val recurring: Boolean? = null,
    @Json(name = "parentId") val parentId: Long? = null,
    @Json(name = "recurringOffset") val recurringOffset: Int? = null,
)
