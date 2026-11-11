package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.CardBenefitSummaryReader;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실적 상태 판정·표시 문구(S15P31E103-373, 2026-11-10 결정). 날짜에 따라 달라지는 판정이라 DB 없이 날짜를 고정해 본다. */
class PerformanceProgressTest {

    static final YearMonth NOV = YearMonth.of(2026, 11);
    /** 기준 30만·50만원. */
    static final CardBenefitSummaryReader.Summary TWO_TIERS = new CardBenefitSummaryReader.Summary(List.of(),
            List.of(300_000L, 500_000L), List.of());

    @Test
    void 기준이_없으면_실적_행이_없어도_NO_TARGET이고_남은_금액은_0이다() {
        PerformanceService.Progress noSummary = PerformanceService.progress(null, null, NOV, NOV.atDay(20));
        PerformanceService.Progress empty = PerformanceService.progress(
                new CardBenefitSummaryReader.Summary(List.of(), List.of(), List.of()), 120_000L, NOV, NOV.atDay(20));

        assertThat(noSummary).isEqualTo(new PerformanceService.Progress(null, 0, 0, PerformanceStatus.NO_TARGET));
        assertThat(empty).isEqualTo(new PerformanceService.Progress(null, 120_000, 0, PerformanceStatus.NO_TARGET));
    }

    @Test
    void 실적_행이_없으면_INSUFFICIENT_DATA이고_목표는_첫_기준이다() {
        assertThat(PerformanceService.progress(TWO_TIERS, null, NOV, NOV.atDay(20)))
                .isEqualTo(new PerformanceService.Progress(300_000L, 0, 300_000, PerformanceStatus.INSUFFICIENT_DATA));
    }

    @Test
    void 목표는_실적보다_큰_가장_작은_기준이고_모두_넘으면_ACHIEVED다() {
        assertThat(PerformanceService.progress(TWO_TIERS, 350_000L, NOV, NOV.atDay(28)).target()).isEqualTo(500_000L);
        assertThat(PerformanceService.progress(TWO_TIERS, 500_000L, NOV, NOV.atDay(2)))
                .isEqualTo(new PerformanceService.Progress(500_000L, 500_000, 0, PerformanceStatus.ACHIEVED));
    }

    @Test
    void 첫_5일은_추세를_보지_않고_ON_TRACK이다() {
        assertThat(PerformanceService.progress(TWO_TIERS, 0L, NOV, NOV.atDay(5)).status())
                .isEqualTo(PerformanceStatus.ON_TRACK);
        // 6일부터는 추세: 0원이면 못 채움
        assertThat(PerformanceService.progress(TWO_TIERS, 0L, NOV, NOV.atDay(6)).status())
                .isEqualTo(PerformanceStatus.AT_RISK);
    }

    @Test
    void 이번_달은_지금_추세가_목표보다_작으면_AT_RISK다() {
        // 11월(30일) 10일까지 10만원 → 추세 30만원 = 목표 30만원 → 채움
        assertThat(PerformanceService.progress(TWO_TIERS, 100_000L, NOV, NOV.atDay(10)).status())
                .isEqualTo(PerformanceStatus.ON_TRACK);
        // 99,999원 → 추세 299,997원 < 30만원
        assertThat(PerformanceService.progress(TWO_TIERS, 99_999L, NOV, NOV.atDay(10)).status())
                .isEqualTo(PerformanceStatus.AT_RISK);
    }

    @Test
    void 지난_달은_못_채웠으면_AT_RISK_다음_달은_ON_TRACK이다() {
        LocalDate today = NOV.atDay(3);
        assertThat(PerformanceService.progress(TWO_TIERS, 299_999L, NOV.minusMonths(1), today).status())
                .isEqualTo(PerformanceStatus.AT_RISK);
        assertThat(PerformanceService.progress(TWO_TIERS, 0L, NOV.plusMonths(1), NOV.atDay(28)).status())
                .isEqualTo(PerformanceStatus.ON_TRACK);
    }

    @Test
    void 기준_표시는_만원_단위다() {
        assertThat(PerformanceService.label(300_000L)).isEqualTo("30만원 이상");
        assertThat(PerformanceService.label(15_000L)).isEqualTo("1만 5,000원 이상");
        assertThat(PerformanceService.label(5_000L)).isEqualTo("5,000원 이상");
        assertThat(PerformanceService.label(12_000_000L)).isEqualTo("1,200만원 이상");
        assertThat(PerformanceService.label(1L)).isEqualTo("1원 이상");
        assertThat(PerformanceService.label(null)).isNull();
    }

    @Test
    void 다음_달_영향_문구() {
        PerformanceService.Progress behind = PerformanceService.progress(TWO_TIERS, 350_000L, NOV, NOV.atDay(28));
        PerformanceService.Progress done = PerformanceService.progress(TWO_TIERS, 600_000L, NOV, NOV.atDay(28));
        PerformanceService.Progress none = PerformanceService.progress(null, 0L, NOV, NOV.atDay(28));
        PerformanceService.Progress noRow = PerformanceService.progress(TWO_TIERS, null, NOV, NOV.atDay(28));

        assertThat(PerformanceService.nextMonthImpact(behind, List.of()).message())
                .isEqualTo("150,000원 더 쓰면 다음 달 50만원 이상 구간 혜택을 받아요");
        assertThat(PerformanceService.nextMonthImpact(done, List.of()).message())
                .isEqualTo("다음 달에 50만원 이상 구간 혜택을 받아요");
        assertThat(PerformanceService.nextMonthImpact(none, List.of())).isNull();
        assertThat(PerformanceService.nextMonthImpact(noRow, List.of())).isNull();
    }
}
