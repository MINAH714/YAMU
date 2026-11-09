package com.yamu.app.pos

import com.yamu.app.core.network.ApiResult

/** 시험용 저장소 — 정해 둔 응답을 차례로 돌려주고, 보낸 시도와 물어본 결과를 적어 둔다 */
class ScriptedPosRepository(
    requests: List<ApiResult<Long>> = emptyList(),
    results: List<ApiResult<PosResult>> = emptyList(),
) : PosRepository {
    private val requestQueue = ArrayDeque(requests)
    private val resultQueue = ArrayDeque(results)

    /** 결제 요청으로 보낸 시도(키 · 본문) */
    val sent = mutableListOf<PosAttempt>()

    /** 결과 조회로 물어본 (paymentId, posCode) */
    val asked = mutableListOf<Pair<Long, String>>()

    override val hasKey: Boolean = true

    override suspend fun merchantFor(terminal: PosTerminal): ApiResult<PosMerchant> = error("이 시험에서는 부르지 않는다")

    override suspend fun request(attempt: PosAttempt): ApiResult<Long> {
        sent += attempt
        return requestQueue.removeFirst()
    }

    override suspend fun result(paymentId: Long, posCode: String): ApiResult<PosResult> {
        asked += paymentId to posCode
        return resultQueue.removeFirst()
    }
}
