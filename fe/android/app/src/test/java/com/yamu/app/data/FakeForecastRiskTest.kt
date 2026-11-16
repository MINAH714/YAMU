package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.RiskLevel
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
    // 시드 사용자, 두 계좌 모두 — 아래 판정은 두 계좌를 합친 시드 숫자 기준이다(잔고예보 기본도 전체, S15P31E103-548)
    private val fake = FakeForecastRepository(FakeBank(FakeUser(storage = null))).apply { select(ForecastSelection(all = true)) }

    @Test fun `기본 데이터 — 생활비 선만 내려가 WARNING, 구간 선은 두 선 중 하나`() = runTest {
        val f = (fake.forecast() as ApiResult.Success).data
        assertEquals(RiskLevel.WARNING, f.status)
        assertTrue(f.riskWindows.isNotEmpty())
        assertTrue(f.riskWindows.all { it.line == FlowConfidence.CONSERVATIVE && it.level == RiskLevel.WARNING })
    }

    @Test fun `확정 선은 기준 위인데 AI 생활비 제외 선이 내려가면 DANGER — 전에는 WARNING`() = runTest {
        // 오늘 신한 ·5678에서 150,000원 — 확정 선 끝 205,000(기준 위), 진행 중 청구까지 뺀 expected 끝 55,000(기준 아래).
        // 전에는 가상 구매로 확인했지만 가상 구매는 이제 카드 출금 계좌 하나만 봐서(-548) 두 계좌 합으로 보려고 예정 지출로 넣는다
        assertEquals(RiskLevel.WARNING, (fake.forecast() as ApiResult.Success).data.status)
        fake.createItem(CashflowForm(title = "마트", amount = 150_000, date = LocalDate.now(KST), accountId = 2), "risk-1")
        assertEquals(RiskLevel.DANGER, (fake.forecast() as ApiResult.Success).data.status)
    }
}
