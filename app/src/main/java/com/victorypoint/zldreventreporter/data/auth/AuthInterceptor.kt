package com.victorypoint.zldreventreporter.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenStore: TokenStore,
    private val authRepository: AuthRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.accessToken ?: return chain.proceed(chain.request())

        val response = chain.proceed(requestWithToken(chain, token))

        if (response.code == 401) {
            response.close()
            val refreshed = runBlocking { authRepository.refresh() }
            if (refreshed.isFailure) {
                authRepository.emitSessionExpired()
                return chain.proceed(chain.request())
            }
            val newToken = tokenStore.accessToken ?: run {
                authRepository.emitSessionExpired()
                return chain.proceed(chain.request())
            }
            return chain.proceed(requestWithToken(chain, newToken))
        }

        return response
    }

    private fun requestWithToken(chain: Interceptor.Chain, token: String) =
        chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
}
