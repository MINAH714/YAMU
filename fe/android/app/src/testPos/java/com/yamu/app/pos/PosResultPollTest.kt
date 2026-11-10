package com.yamu.app.pos

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.payment.data.posErrorMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 결과 기다리기(M-1). 웹 POS WaitingStep처럼 APPROVED · EXPIRED · CANCELED · retryable=false인 DECLINED에서 멈춘다 */
@OptIn(ExperimentalCoroutinesApi::class)
class PosResultPollTest {
    private fun dto(status: String, retryable: Boolean? = null, failure: String? = null, code: String = "INSUFFICIENT_BALANCE"): ApiResult<PosResult> =
        ApiResult.Success(
            PosResultDto(
                paymentId = 9,
                status = status,
                amount = 15_000,
                failure = failure?.let { PosFailureDto(code, it) },
                retryable = retryable,
            ).toResult(),
        )

    /** 출금 미확인으로 만료된 결과(BE가 늦은 출금을 찾으면 APPROVED로 바뀐다) */
    private fun lateExpired() = dto("EXPIRED", failure = "결제 결과를 확인하지 못했습니다.", code = POS_WITHDRAWAL_NOT_CONFIRMED)

    private suspend fun TestScope.poll(repo: ScriptedPosRepository): List<PosResultState> {
        val states = mutableListOf<PosResultState>()
        pollPosResult(repo, 9, "POS-YAMU-CAFE", now = { currentTime }) { states += it }
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

    @Test fun `고객이 앱에서 거절하면 마감을 기다리지 않고 바로 멈춘다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("REQUESTED"), dto("REJECTED"), dto("EXPIRED")))
        val states = poll(repo)
        assertEquals(PosStatus.REJECTED, states.last().result?.status)
        assertEquals(2, repo.asked.size)
        assertFalse(states.last().stopped)   // 오류로 멈춘 게 아니라 최종 결과라 영수증 쪽 화면이 뜬다
    }

    @Test fun `접수 여부를 몰라 다시 보낸 결제가 고객 거절이어도 대기 요청은 남지 않는다`() = runTest {
        val store = MemoryPosPendingStore()
        val repo = ScriptedPosRepository(
            requests = listOf(ApiResult.Failure(ApiError.Network), ApiResult.Success(9)),
            results = listOf(dto("REJECTED")),
        )
        val sender = PosRequestSender(repo, PosMerchant(12, "POS-YAMU-CAFE", "야무 테스트 카페"), 15_000, store)
        sender.send("YAMU8F2A")
        assertNotNull(store.value)
        // 그 사이 고객이 앱에서 거절했다. BE는 같은 키 · 같은 본문에 처음 paymentId를 돌려주고, 결과 조회는 REJECTED다
        val requested = sender.resend() as PosSendOutcome.Requested
        assertNull(store.value)   // paymentId를 받는 순간 지운다
        val states = mutableListOf<PosResultState>()
        pollPosResult(repo, requested.paymentId, requested.posCode, now = { currentTime }) { states += it }
        assertEquals(PosStatus.REJECTED, states.single().result?.status)
        assertNull(store.value)
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

    @Test fun `출금 미확인으로 거절 · 만료돼도 멈추지 않고 늦은 승인을 받는다`() = runTest {
        val repo = ScriptedPosRepository(
            results = listOf(
                dto("DECLINED", retryable = false, failure = "결제 결과를 확인하지 못했습니다.", code = POS_WITHDRAWAL_NOT_CONFIRMED),
                lateExpired(),
                dto("APPROVED"),
            ),
        )
        val states = poll(repo)
        assertEquals(listOf(PosStatus.CHECKING, PosStatus.CHECKING, PosStatus.APPROVED), states.map { it.result?.status })
        assertTrue(states.none { it.stopped })
    }

    @Test fun `출금 확인 중이 11분을 넘기면 멈추고 고객 앱을 확인하라고 한다`() = runTest {
        val repo = ScriptedPosRepository(results = List(400) { lateExpired() })
        val start = currentTime
        val last = poll(repo).last()
        assertTrue(last.stopped)
        assertEquals(POS_LATE_CHECK_OVER, last.error)
        assertEquals(PosStatus.CHECKING, last.result?.status)
        assertEquals(POS_LATE_CHECK_MS, currentTime - start)
        assertEquals((POS_LATE_CHECK_MS / 2_000 + 1).toInt(), repo.asked.size)
    }

    @Test fun `출금 확인 중에 연결이 끊겨도 처음 본 때부터 잰다`() = runTest {
        val repo = ScriptedPosRepository(
            results = listOf(lateExpired()) + List(400) { ApiResult.Failure(ApiError.Network) },
        )
        val start = currentTime
        val last = poll(repo).last()
        assertTrue(last.stopped)
        assertEquals(POS_LATE_CHECK_OVER, last.error)
        assertEquals(POS_LATE_CHECK_MS, currentTime - start)
    }

    @Test fun `응답이 온 뒤 2초마다 묻는다`() = runTest {
        val repo = ScriptedPosRepository(results = listOf(dto("REQUESTED"), dto("REQUESTED"), dto("APPROVED")))
        val start = currentTime
        poll(repo)
        assertEquals(4_000L, currentTime - start)
    }
}
