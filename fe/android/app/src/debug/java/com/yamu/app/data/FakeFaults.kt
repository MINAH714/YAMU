package com.yamu.app.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import kotlinx.coroutines.delay

/**
 * 디버그 메뉴 '모든 기능 상태'(DevSession.faultScenario)를 가짜 저장소의 '화면이 불러오는 값'에 건다 —
 * 04 문서 '데이터 상태 확인표'를 서버 없이 화면마다 그려 보기 위한 것(release에는 들어가지 않음).
 * [empty]는 새 사용자(연결 전)일 때 서버가 줄 응답 — 없으면 '데이터 없음'에서도 평소 값.
 */
internal suspend fun <T> withFault(empty: (() -> ApiResult<T>)? = null, load: suspend () -> ApiResult<T>): ApiResult<T> =
    when (DevSession.faultScenario) {
        FaultScenario.NONE -> load()
        FaultScenario.SLOW -> {
            delay(3_000)
            load()
        }
        FaultScenario.EMPTY -> empty?.let {
            delay(300)
            it()
        } ?: load()
        FaultScenario.BANK_ERROR -> failAfterDelay(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
        FaultScenario.NETWORK_ERROR -> failAfterDelay(ApiError.Network)
        FaultScenario.DATA_INSUFFICIENT -> failAfterDelay(ApiError.DataInsufficient("DATA_INSUFFICIENT"))
    }

private suspend fun failAfterDelay(error: ApiError): ApiResult<Nothing> {
    delay(300)
    return ApiResult.Failure(error)
}
