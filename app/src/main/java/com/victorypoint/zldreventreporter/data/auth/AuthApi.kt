package com.victorypoint.zldreventreporter.data.auth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

private const val TOKEN_URL = "auth/realms/zwift/protocol/openid-connect/token"
private const val CLIENT_ID = "Zwift_Mobile_Link"

interface AuthApi {
    @FormUrlEncoded
    @POST(TOKEN_URL)
    suspend fun login(
        @Field("client_id") clientId: String = CLIENT_ID,
        @Field("grant_type") grantType: String = "password",
        @Field("username") username: String,
        @Field("password") password: String,
    ): TokenResponse

    @FormUrlEncoded
    @POST(TOKEN_URL)
    suspend fun refresh(
        @Field("client_id") clientId: String = CLIENT_ID,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String,
    ): TokenResponse
}

@JsonClass(generateAdapter = true)
data class TokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long,
    @Json(name = "refresh_expires_in") val refreshExpiresIn: Long = 0L,
)
