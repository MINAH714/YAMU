package com.yamu.app.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET

/** 서버 연결 확인(GET /api/health, 디버그 메뉴) — 공통 응답 형식이 아니라 {"status":"ok"}만 온다 */
interface HealthApi {
    @GET("health") suspend fun health(): Response<HealthResponse>
}

@Serializable data class HealthResponse(val status: String? = null)
