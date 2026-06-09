package com.victorypoint.zldreventreporter.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_FILE = "zldr_reporter_tokens"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_ACCESS_EXPIRY = "access_expiry_ms"
private const val KEY_USERNAME = "username"

class TokenStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var accessExpiryMs: Long
        get() = prefs.getLong(KEY_ACCESS_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_ACCESS_EXPIRY, value).apply()

    fun isAccessTokenValid(): Boolean =
        accessToken != null && System.currentTimeMillis() < accessExpiryMs - 30_000

    fun hasRefreshToken(): Boolean = refreshToken != null

    fun save(response: TokenResponse) {
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        accessExpiryMs = System.currentTimeMillis() + response.expiresIn * 1000
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
