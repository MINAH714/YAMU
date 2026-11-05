package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 시뮬레이션 안내 문장(DB 없음). 구매를 권하거나 말리는 말 없이 최저 잔액과 기간 밖 금액만 알린다. */
class PurchaseSimulationMessageTest {
    private static final LocalDate NOV_16 = LocalDate.of(2026, 11, 16);

    @Test
    void 최저_잔액과_기간_밖_금액을_알린다() {
        assertThat(PurchaseSimulationService.message(simulation(12_000L, -800_000L)))
                .isEqualTo("11월 16일 잔액이 12,000원까지 내려가요. 예보 기간 밖에서 800,000원이 더 빠져요.");
    }

    @Test
    void 잔액이_마이너스면_모자란_금액을_알린다() {
        assertThat(PurchaseSimulationService.message(simulation(-50_000L, 0L)))
                .isEqualTo("11월 16일 잔액이 50,000원 모자라요.");
    }

    private static PurchaseSimulation simulation(long afterMin, long beyond) {
        PurchaseSimulation.Snapshot before = new PurchaseSimulation.Snapshot(412_000L, NOV_16, 3_258_000L,
                RiskDetector.Level.SAFE);
        PurchaseSimulation.Snapshot after = new PurchaseSimulation.Snapshot(afterMin, NOV_16, 2_858_000L,
                RiskDetector.Level.DANGER);
        return new PurchaseSimulation(List.of(), new PurchaseSimulation.Comparison(before, after, -400_000L, beyond),
                List.of(), null, List.of());
    }
}
