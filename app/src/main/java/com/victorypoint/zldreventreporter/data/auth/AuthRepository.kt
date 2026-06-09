package com.victorypoint.zldreventreporter.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

class AuthRepository(
    private val authApi: AuthApi,
    val tokenStore: TokenStore,
) {
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired = _sessionExpired.asSharedFlow()

    suspend fun login(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = authApi.login(username = username, password = password)
                tokenStore.save(response)
                tokenStore.username = username
            }
        }

    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        val rt = tokenStore.refreshToken
            ?: return@withContext Result.failure(Exception("No refresh token"))
        runCatching {
            val response = authApi.refresh(refreshToken = rt)
            tokenStore.save(response)
        }
    }

    fun logout() {
        tokenStore.clear()
    }

    fun emitSessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }
}
