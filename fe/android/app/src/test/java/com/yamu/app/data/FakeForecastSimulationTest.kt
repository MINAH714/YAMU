package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.SimulationQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * debug 가짜 가상 구매가 체크카드 잔액 초과 안내를 BE(-347 !198)처럼 붙이는지.
 * 가짜 계좌는 월급 통장 412,000 · 생활비 통장 268,000이고, 가상 구매는 생활비 통장에서 빠진다
 */
class FakeForecastSimulationTest {
    private val fake = FakeForecastRepository()
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

    @Test fun `출금 계좌가 마이너스면 다른 계좌 잔액이 있어도 안내한다(!206 리뷰)`() = runTest {
        // 생활비 통장 268,000 − 300,000 = −32,000. 두 계좌를 합치면 380,000이라 합계로 보면 안내가 빠졌다
        val s = simulate(300_000, cardId = 16)
        assertTrue(s.message!!.endsWith(notice))
        assertTrue(s.days.first().after!! > 0)
    }

    @Test fun `출금 계좌 잔액이 남거나 신용카드면 안내하지 않는다`() = runTest {
        assertFalse(simulate(200_000, cardId = 16).message!!.contains(notice))   // 생활비 통장 68,000 남음
        assertFalse(simulate(300_000, cardId = 11).message!!.contains(notice))   // 신용카드는 구매일에 빠지지 않는다
    }
}
