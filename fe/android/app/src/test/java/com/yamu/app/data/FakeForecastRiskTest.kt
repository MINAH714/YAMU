package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.SimulationQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * debug 가짜 잔고예보가 BE -356 !223의 두 선 판정을 따르는지 — expected('AI 생활비 제외') 미만이면 DANGER,
 * conservative('AI 생활비 반영')만 미만이면 WARNING, 구간의 선에 CONFIRMED는 없다
 */
class FakeForecastRiskTest {
    private val fake = FakeForecastRepository()

    @Test fun `기본 데이터 — 생활비 선만 내려가 WARNING, 구간 선은 두 선 중 하나`() = runTest {
        val f = (fake.forecast() as ApiResult.Success).data
        assertEquals(RiskLevel.WARNING, f.status)
        assertTrue(f.riskWindows.isNotEmpty())
        assertTrue(f.riskWindows.all { it.line == FlowConfidence.CONSERVATIVE && it.level == RiskLevel.WARNING })
    }

    @Test fun `확정 선은 기준 위인데 AI 생활비 제외 선이 내려가면 DANGER — 전에는 WARNING`() = runTest {
        // 오늘 체크카드 150,000원 — 확정 선 끝 205,000(기준 위), 진행 중 청구까지 뺀 expected 끝 55,000(기준 아래)
        val query = SimulationQuery(150_000, null, "HYPERMARKET", "대형마트", null, null, PaymentChannel.OFFLINE, 16, null, LocalDate.now(KST))
        val s = (fake.simulate(query) as ApiResult.Success).data
        assertEquals(RiskLevel.WARNING, s.before?.status)
        assertEquals(RiskLevel.DANGER, s.after?.status)
    }
}
