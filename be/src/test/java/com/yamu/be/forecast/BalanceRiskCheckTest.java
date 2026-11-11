package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 잔고 위험 알림용 판정(Task 9.3.2). 재료는 잔고예보 조회와 같은 {@code prepare}를 쓰고, 여기서는 그 재료로 고르는 구간만 본다.
 * 계산 자체(선·위험 수준)는 {@code RiskDetectorTest}·{@code ForecastServiceTest}가 본다.
 */
class BalanceRiskCheckTest {

    static final long USER = 1L;
    static final long A = 11L;
    static final long B = 12L;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    ForecastService forecasts = mock(ForecastService.class);
    BalanceRiskCheck check = new BalanceRiskCheck(forecasts);

    @Test
    void 통합은_안전해도_계좌_하나가_위험하면_그_계좌의_구간을_돌려준다() {
        // 명세 예시: A 800,000원에서 11/5 예정 출금 200,000원, B 300,000원에서 11/5 카드 청구 400,000원. 안전 잔액 100,000원
        given(Map.of(A, 800_000L, B, 300_000L), List.of(
                event(A, NOV_5, -200_000L, FlowEvent.Type.SCHEDULED_OUTFLOW),
                event(B, NOV_5, -400_000L, FlowEvent.Type.CARD_BILLING)));

        BalanceRiskCheck.Risk risk = check.firstRisk(USER, List.of(A, B)).orElseThrow();

        assertThat(risk.accountId()).isEqualTo(B);
        assertThat(risk.today()).isEqualTo(TODAY);
        assertThat(risk.from()).isEqualTo(NOV_5);
        assertThat(risk.level()).isEqualTo("DANGER");
        assertThat(risk.lowestBalance()).isEqualTo(-100_000L);
        assertThat(risk.shortfall()).isEqualTo(200_000L);
        assertThat(risk.threshold()).isEqualTo(100_000L);
    }

    @Test
    void 통합과_계좌의_첫날이_같으면_통합_구간을_고른다() {
        given(Map.of(A, 50_000L, B, 30_000L), List.of());

        BalanceRiskCheck.Risk risk = check.firstRisk(USER, List.of(A, B)).orElseThrow();

        assertThat(risk.accountId()).isNull();
        assertThat(risk.from()).isEqualTo(TODAY);
        assertThat(risk.shortfall()).isEqualTo(20_000L);
    }

    @Test
    void 가장_이른_구간을_고른다() {
        given(Map.of(A, 800_000L, B, 300_000L), List.of(
                event(B, NOV_5, -400_000L, FlowEvent.Type.CARD_BILLING),
                event(A, TODAY.plusDays(3), -750_000L, FlowEvent.Type.SCHEDULED_OUTFLOW)));

        BalanceRiskCheck.Risk risk = check.firstRisk(USER, List.of(A, B)).orElseThrow();

        assertThat(risk.accountId()).isEqualTo(A);
        assertThat(risk.from()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void 위험이_없으면_empty다() {
        given(Map.of(A, 800_000L), List.of(event(A, NOV_5, -200_000L, FlowEvent.Type.SCHEDULED_OUTFLOW)));

        assertThat(check.firstRisk(USER, List.of(A))).isEmpty();
    }

    private void given(Map<Long, Long> balances, List<FlowEvent> events) {
        List<Long> selected = balances.keySet().stream().sorted().toList();
        BalanceForecast.Horizon horizon = new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, TODAY,
                TODAY.plusDays(14), 14);
        when(forecasts.today()).thenReturn(TODAY);
        Map<Long, Long> start = new LinkedHashMap<>();
        selected.forEach(id -> start.put(id, balances.get(id)));
        when(forecasts.prepare(eq(USER), eq(selected), any(), any(), anyBoolean(), eq(TODAY))).thenReturn(
                new ForecastService.Prepared(selected, horizon, 100_000L, Map.of(), null, events, List.of(), start,
                        Map.of(), List.of(), List.of()));
    }

    private static FlowEvent event(long accountId, LocalDate date, long amount, FlowEvent.Type type) {
        return new FlowEvent(accountId, date, amount, type, FlowEvent.Confidence.CONFIRMED, type.name(), null);
    }
}
