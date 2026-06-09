package com.victorypoint.zldreventreporter.data.events.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ResultDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "profileId") val profileId: Long? = null,
    @Json(name = "activityId") val activityId: Long? = null,
    @Json(name = "subgroupId") val subgroupId: Long? = null,
    @Json(name = "position") val position: Int? = null,
    @Json(name = "finishTime") val finishTime: Long? = null,
)
