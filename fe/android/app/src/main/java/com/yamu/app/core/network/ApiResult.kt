package com.yamu.app.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.io.IOException

/** API 호출 결과 — 화면 코드는 HTTP 코드 · 예외를 보지 않고 이것만 본다 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

/**
 * 서버 오류 코드 → 앱 오류 종류 (07 문서 3-2 표).
 * [code]는 서버 코드 그대로(기능 화면이 'DUPLICATE_EMAIL'처럼 자기 코드를 구분), [details]는 서버 error.details.
 */
sealed interface ApiError {
    val code: String
    val details: JsonObject?

    data class InvalidRequest(override val code: String, override val details: JsonObject? = null) : ApiError
    data class Unauthorized(override val code: String, override val details: JsonObject? = null) : ApiError
    data class StepUpRequired(override val code: String, override val details: JsonObject? = null) : ApiError
    data class NotFound(override val code: String, override val details: JsonObject? = null) : ApiError
    data class Conflict(override val code: String, override val details: JsonObject? = null) : ApiError
    /** 423 — 본인 인증 5회 실패 등으로 잠김 */
    data class Locked(override val code: String, override val details: JsonObject? = null) : ApiError
    data class DataInsufficient(override val code: String, override val details: JsonObject? = null) : ApiError
    /** SSAFY 금융망이 늦거나 실패 — 마지막 값과 기준 시각을 보여 준다 */
    data class BankUnavailable(override val code: String, override val details: JsonObject? = null) : ApiError
    data class Server(override val code: String, override val details: JsonObject? = null) : ApiError
    data object Network : ApiError {
        override val code = "NETWORK"
        override val details: JsonObject? = null
    }
    data class Unknown(override val code: String, val httpStatus: Int? = null, override val details: JsonObject? = null) : ApiError

    /** details 안의 숫자(예: remainingAttempts) */
    fun detailInt(key: String): Int? = details?.get(key)?.jsonPrimitive?.intOrNull

    /** details 안의 글자(예: unlockAt) */
    fun detailString(key: String): String? = details?.get(key)?.jsonPrimitive?.contentOrNull

    companion object {
        fun from(httpStatus: Int, code: String?, details: JsonObject? = null): ApiError {
            val c = code.orEmpty()
            return when (c) {
                "INVALID_REQUEST" -> InvalidRequest(c, details)
                "UNAUTHORIZED", "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN" -> Unauthorized(c, details)
                "STEP_UP_REQUIRED" -> StepUpRequired(c, details)
                "RESOURCE_NOT_FOUND" -> NotFound(c, details)
                "IDEMPOTENCY_KEY_REUSED" -> Conflict(c, details)
                "DATA_INSUFFICIENT" -> DataInsufficient(c, details)
                "FIN_API_UNAVAILABLE", "FIN_API_ERROR", "UPSTREAM_TIMEOUT" -> BankUnavailable(c, details)
                else -> when (httpStatus) {
                    400 -> InvalidRequest(c, details)
                    401 -> Unauthorized(c, details)
                    403 -> StepUpRequired(c, details)
                    404 -> NotFound(c, details)
                    409 -> Conflict(c, details)
                    422 -> DataInsufficient(c, details)
                    423 -> Locked(c, details)
                    502, 504 -> BankUnavailable(c, details)
                    in 500..599 -> Server(c, details)
                    else -> Unknown(c, httpStatus, details)
                }
            }
        }
    }
}

/**
 * Retrofit 호출을 ApiResult로 — 모든 Repository가 이걸로 부른다.
 * 예: `apiCall { api.home() }`
 */
suspend fun <T> apiCall(block: suspend () -> Response<ApiEnvelope<T>>): ApiResult<T> = guarded {
    val response = block()
    val body = response.body()
    val data = body?.data
    when {
        response.isSuccessful && body?.success == true && data != null -> ApiResult.Success(data)
        response.isSuccessful && body?.success == true -> ApiResult.Failure(ApiError.Unknown("EMPTY_DATA", response.code()))
        else -> response.failure(body)
    }
}

/** 돌려줄 data가 없는 API(204 No Content · data: null) — 간편 비밀번호 설정 · 로그아웃 등 */
suspend fun apiCallNoData(block: suspend () -> Response<ApiEnvelope<JsonElement>>): ApiResult<Unit> = guarded {
    val response = block()
    val body = response.body()
    if (response.isSuccessful && (body == null || body.success)) ApiResult.Success(Unit) else response.failure(body)
}

private inline fun <T> guarded(block: () -> ApiResult<T>): ApiResult<T> = try {
    block()
} catch (e: IOException) {
    ApiResult.Failure(ApiError.Network)
} catch (e: SerializationException) {
    ApiResult.Failure(ApiError.Unknown("PARSE_ERROR"))
}

private fun Response<*>.failure(body: ApiEnvelope<*>?): ApiResult.Failure {
    val error = body?.error ?: parseErrorBody(errorBody()?.string())
    return ApiResult.Failure(ApiError.from(code(), error?.code, error?.details))
}

private fun parseErrorBody(raw: String?): ApiErrorBody? =
    if (raw.isNullOrBlank()) null
    else runCatching { YamuJson.decodeFromString<ApiEnvelope<JsonElement>>(raw).error }.getOrNull()
