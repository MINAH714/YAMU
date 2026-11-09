package com.yamu.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 서버 JSON 규칙 — 모르는 필드는 무시, 없는 필드는 기본값(명세가 바뀌어도 앱이 죽지 않게) */
val YamuJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** 모든 YAMU API 응답의 겉모양 — API 명세 1장 '응답 형식' */
@Serializable
data class ApiEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val error: ApiErrorBody? = null,
)

@Serializable
data class ApiErrorBody(
    val code: String = "",
    val message: String? = null,
    val details: JsonObject? = null,
)

/** 공통 헤더 — API 명세 1장 '공통 헤더' */
object YamuHeaders {
    const val AUTHORIZATION = "Authorization"
    const val STEP_UP_TOKEN = "X-Step-Up-Token"
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
}

/** 결제 승인 · 취소 · 계좌 연결에 붙이는 멱등 키. 같은 동작을 다시 시도할 땐 **같은 키**를 다시 쓴다 */
fun newIdempotencyKey(): String = java.util.UUID.randomUUID().toString()
