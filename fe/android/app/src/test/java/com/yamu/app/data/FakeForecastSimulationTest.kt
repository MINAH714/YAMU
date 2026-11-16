package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.SimulationQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * debug 가짜 가상 구매가 체크카드 잔액 초과 안내를 BE(-347 !198)처럼 붙이는지.
 * 가짜 계좌는 KB ·1234 412,000 · 신한 ·5678 268,000이고, 체크카드 알뜰체크(16)는 KB ·1234에서 빠진다.
 * 가상 구매는 연결 계좌 전체 · 30일로 계산하고, 카드 출금 계좌 하나만 보면 모자라는지 계좌별 전후로 알린다(S15P31E103-589) —
 * 잔고예보에서 고른 계좌 · 기간과 상관없다
 */
class FakeForecastSimulationTest {
    // 시드 사용자, 잔고예보에서는 신한 ·5678 하나 · 7일만 골라 둔다 — 그래도 가상 구매는 계좌 전체 · 30일이다
    private val fake = FakeForecastRepository(FakeBank(FakeUser(storage = null))).apply { select(ForecastSelection(ForecastPeriod.DAYS_7, setOf(2))) }
    private val notice = "체크카드는 구매일 잔액보다 큰 금액은 결제되지 않을 수 있어요."

    @Test fun `가짜 서버도 제외 업종을 지원하지 않는다`() = runTest {
        val categories = (fake.categories() as ApiResult.Success).data
        assertFalse(categories.single { it.code == "TAXI" }.simulationSupported)
        for (category in listOf("ELECTRIC_UTILITY", "UNIVERSITY", "TAXI")) {
            val query = SimulationQuery(10_000, null, category, category, null, null,
                PaymentChannel.OFFLINE, null, null, LocalDate.now(KST))
            assertTrue(fake.simulate(query) is ApiResult.Failure)
        }
    }

    private suspend fun simulate(amount: Long, cardId: Long): Simulation {
        val query = SimulationQuery(amount, null, "HYPERMARKET", "대형마트", null, null, PaymentChannel.OFFLINE, cardId, null, LocalDate.now(KST))
        return (fake.simulate(query) as ApiResult.Success).data
    }

    @Test fun `계좌 전체 · 30일로 그리고, 출금 계좌가 마이너스면 어느 계좌에 얼마인지 알린다(-589)`() = runTest {
        // 두 계좌 합 680,000 − 450,000 = 230,000이라 그래프는 0원 위지만, KB ·1234만 보면 412,000 − 450,000 = −38,000
        val s = simulate(450_000, cardId = 16)
        assertTrue(s.message!!.endsWith(notice))
        assertEquals(listOf(1L, 2L), s.accounts.map { it.accountId })
        assertEquals(LocalDate.now(KST).plusDays(30), s.days.last().date)   // 오늘부터 30일, 끝 날 포함
        assertTrue(s.days.first().after!! > 0)
        val shortfall = s.cardAccountShortfall!!
        assertEquals(1L, shortfall.account.accountId)
        assertTrue(shortfall.amount >= 38_000)   // 그 뒤 생활비 · 예정 지출로 더 내려갈 수 있다
        assertTrue(shortfall.text.startsWith("이 카드로 결제하면 KB국민은행 ··1234 계좌에 "))
    }

    @Test fun `출금 계좌 잔액이 남거나 신용카드면 안내하지 않는다`() = runTest {
        assertFalse(simulate(200_000, cardId = 16).message!!.contains(notice))   // KB ·1234 212,000 남음
        assertFalse(simulate(450_000, cardId = 11).message!!.contains(notice))   // 신용카드는 구매일에 빠지지 않는다
    }
}
