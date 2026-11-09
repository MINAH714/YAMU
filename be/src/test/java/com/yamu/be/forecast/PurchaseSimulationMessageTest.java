package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 시뮬레이션 안내 문장(DB 없음). 구매를 권하거나 말리는 말 없이 최저 잔액과 기간 밖 금액만 알린다. */
class PurchaseSimulationMessageTest {
    private static final LocalDate NOV_16 = LocalDate.of(2026, 11, 16);

    @Test
    void 최저_잔액과_기간_밖_금액을_알린다() {
        assertThat(PurchaseSimulationService.message(simulation(12_000L, -800_000L, false)))
                .isEqualTo("11월 16일 잔액이 12,000원까지 내려가요. 예보 기간 밖에서 800,000원이 더 빠져요.");
    }

    @Test
    void 잔액이_마이너스면_모자란_금액을_알린다() {
        assertThat(PurchaseSimulationService.message(simulation(-50_000L, 0L, false)))
                .isEqualTo("11월 16일 잔액이 50,000원 모자라요.");
    }

    @Test
    void 체크카드_구매일_잔액이_0원_아래면_실제로는_결제되지_않는다고_덧붙인다() {
        assertThat(PurchaseSimulationService.message(simulation(-50_000L, 0L, true)))
                .isEqualTo("11월 16일 잔액이 50,000원 모자라요. 체크카드는 그날 잔액보다 큰 금액은 결제되지 않을 수 있어요.");
    }

    @Test
    void 빠지는_금액이_없으면_전후가_같다고만_알린다() {
        PurchaseSimulation.Snapshot same = new PurchaseSimulation.Snapshot(412_000L, NOV_16, 3_258_000L,
                RiskDetector.Level.SAFE, null);
        // 출금 계좌가 선택 밖이어도 "0원이 빠져"라고 하지 않는다
        assertThat(PurchaseSimulationService.message(new PurchaseSimulation(List.of(),
                new PurchaseSimulation.Comparison(same, same, 0L, 0L), List.of(), null, List.of(), false)))
                .isEqualTo("즉시할인으로 빠지는 금액이 없어 비교 결과는 같아요.");
    }

    private static PurchaseSimulation simulation(long afterMin, long beyond, boolean checkCardOverdrawn) {
        PurchaseSimulation.Snapshot before = new PurchaseSimulation.Snapshot(412_000L, NOV_16, 3_258_000L,
                RiskDetector.Level.SAFE, null);
        PurchaseSimulation.Snapshot after = new PurchaseSimulation.Snapshot(afterMin, NOV_16, 2_858_000L,
                RiskDetector.Level.DANGER, NOV_16);
        List<PurchaseSimulation.ScheduledWithdrawal> schedule = List.of(new PurchaseSimulation.ScheduledWithdrawal(NOV_16,
                -400_000L, "일시불 (11월 청구)", 1, 1, YearMonth.of(2026, 11), 3L, true));
        return new PurchaseSimulation(schedule, new PurchaseSimulation.Comparison(before, after, -400_000L, beyond),
                List.of(), null, List.of(), checkCardOverdrawn);
    }
}
