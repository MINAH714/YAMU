package com.yamu.app.pos

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.payment.data.posErrorMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 결과 기다리기(M-1). 웹 POS WaitingStep처럼 APPROVED · EXPIRED · CANCELED · retryable=false인 DECLINED에서 멈춘다 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosResultPollTest {
    private fun dto(status: String, retryable: Boolean? = null, failure: String? = null): ApiResult<PosResult> =
        ApiResult.Success(
            PosResultDto(
                paymentId = 9,
                status = status,
                amount = 15_000,
                failure = failure?.let { PosFailureDto("INSUFFICIENT_BALANCE", it) },
                retryable = retryable,
            ).toResult(),
        )

    private suspend fun poll(repo: ScriptedPosRepository): List<PosResultState> {
        val states = mutableListOf<PosResultState>()
        pollPosResult(repo, 9, "POS-YAMU-CAFE") { states += it }
        return states
    }

    @Test fun `거절돼도 retryable이면 계속 묻다가 다른 카드 승인에서 멈춘다`() = runTest {
        val repo = ScriptedPosRepository(
            results = listOf(dto("REQUESTED"), dto("DECLINED", retryable = true, failure = "잔액이 부족해요"), dto("PROCESSING"), dto("APPROVED")),
        )
        val states = poll(repo)
        assertEquals(
            listOf(PosStatus.WAITING, PosStatus.RETRYING, PosStatus.PROCESSING, PosStatus.APPROVED),
            states.map { it.result?.status },
        )
        assertEquals("잔액이 부족해요", states[1].result?.failureMessage)
        assertTrue(repo.asked.all { it == 9L to "POS-YAMU-CAFE" })   // 결제를 만든 단말 코드로만 묻는다
    }

    @Test fun `retryable=false인 거절에서 바로 멈춘다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("DECLINED", retryable = false), dto("APPROVED")))
        val states = poll(repo)
        assertEquals(1, repo.asked.size)
        assertEquals(PosStatus.DECLINED, states.single().result?.status)
    }

    @Test fun `만료에서 멈춘다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("REQUESTED"), dto("EXPIRED"), dto("APPROVED")))
        assertEquals(PosStatus.EXPIRED, poll(repo).last().result?.status)
        assertEquals(2, repo.asked.size)
    }

    @Test fun `취소에서 멈춘다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("CANCELED"), dto("APPROVED")))
        assertEquals(PosStatus.CANCELED, poll(repo).single().result?.status)
    }

    @Test fun `연결이 끊겨도 마지막 결과를 둔 채 계속 묻는다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("REQUESTED"), ApiResult.Failure(ApiError.Network), dto("APPROVED")))
        val states = poll(repo)
        assertEquals(3, repo.asked.size)
        assertEquals(PosStatus.WAITING, states[1].result?.status)
        assertEquals("인터넷 연결을 확인해 주세요", states[1].error)
        assertFalse(states[1].stopped)
        assertNull(states[2].error)
    }

    @Test fun `다른 단말이 만든 결제(404)면 멈춘다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(ApiResult.Failure(ApiError.from(404, "RESOURCE_NOT_FOUND")), dto("APPROVED")))
        val state = poll(repo).single()
        assertTrue(state.stopped)
        assertEquals(posErrorMessage("RESOURCE_NOT_FOUND"), state.error)
    }

    @Test fun `단말 키가 틀리면(401) 멈추고 키를 확인하라고 한다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("REQUESTED"), ApiResult.Failure(ApiError.from(401, "INVALID_POS_KEY")), dto("APPROVED")))
        val last = poll(repo).last()
        assertTrue(last.stopped)
        assertEquals(posErrorMessage("INVALID_POS_KEY"), last.error)
        assertEquals(PosStatus.WAITING, last.result?.status)
        assertEquals(2, repo.asked.size)
    }

    @Test fun `응답이 온 뒤 2초마다 묻는다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("REQUESTED"), dto("REQUESTED"), dto("APPROVED")))
        val start = currentTime
        poll(repo)
        assertEquals(4_000L, currentTime - start)
    }
}
