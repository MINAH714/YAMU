package com.yamu.be.payment.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.CardBenefitSummaryReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 결제 승인·상세의 누적·목표가 카드 목록 API와 같은 규칙으로 나오는지(S15P31E103-390). */
class PaymentPerformanceTotalsTest {

    static final long CARD = 7L;
    static final long PRODUCT = 70L;
    static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    CardActivityReader activity = mock(CardActivityReader.class);
    CardBenefitSummaryReader summaries = mock(CardBenefitSummaryReader.class);
    // 2026-10-31 15:30 UTC = 한국 11월 1일 00:30. 혜택 유효 기간은 한국 날짜로 본다
    Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC);
    PaymentPerformanceTotals totals;

    @BeforeEach
    void setUp() {
        totals = new PaymentPerformanceTotals(activity, summaries, clock);
    }

    @Test
    void 누적은_그_실적_월의_합계이고_목표는_누적보다_큰_가장_작은_기준이다() {
        when(activity.recognizedAmounts(List.of(CARD), YearMonth.of(2026, 10))).thenReturn(Map.of(CARD, 320_000L));
        when(summaries.summaries(eq(List.of(PRODUCT)), eq(LocalDate.of(2026, 11, 1))))
                .thenReturn(Map.of(PRODUCT, summary(300_000L, 500_000L)));

        assertThat(totals.of(CARD, PRODUCT, OCTOBER))
                .isEqualTo(new PaymentPerformanceTotals.Total(320_000L, 500_000L));
    }

    @Test
    void 기준을_모두_넘었으면_가장_큰_기준이다() {
        when(activity.recognizedAmounts(any(), any())).thenReturn(Map.of(CARD, 900_000L));
        when(summaries.summaries(any(), any())).thenReturn(Map.of(PRODUCT, summary(300_000L, 500_000L)));

        assertThat(totals.of(CARD, PRODUCT, OCTOBER).targetAmount()).isEqualTo(500_000L);
    }

    @Test
    void 월_실적_행이_없으면_누적은_0원이다() {
        when(activity.recognizedAmounts(any(), any())).thenReturn(Map.of());
        when(summaries.summaries(any(), any())).thenReturn(Map.of(PRODUCT, summary(300_000L)));

        assertThat(totals.of(CARD, PRODUCT, OCTOBER))
                .isEqualTo(new PaymentPerformanceTotals.Total(0L, 300_000L));
    }

    @Test
    void 실적_조건이_없는_카드는_목표가_없다() {
        when(activity.recognizedAmounts(any(), any())).thenReturn(Map.of(CARD, 50_000L));
        when(summaries.summaries(any(), any())).thenReturn(Map.of(PRODUCT, summary()));
        assertThat(totals.of(CARD, PRODUCT, OCTOBER).targetAmount()).isNull();

        // 오늘 유효한 혜택이 없는 상품은 요약이 없다
        when(summaries.summaries(any(), any())).thenReturn(Map.of());
        assertThat(totals.of(CARD, PRODUCT, OCTOBER))
                .isEqualTo(new PaymentPerformanceTotals.Total(50_000L, null));
    }

    private static CardBenefitSummaryReader.Summary summary(Long... thresholds) {
        List<Long> list = List.of(thresholds);
        return new CardBenefitSummaryReader.Summary(List.of(), list,
                list.stream().map(t -> new CardBenefitSummaryReader.Tier(t, List.of(), null)).toList(), 0L);
    }
}
