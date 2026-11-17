package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 유불리 분석 응답 projection.history(S15P31E103-615) — 자연 소비를 낸 달별 같은 기간 인정 실적 */
class PerformanceAnalysisHistoryTest {

    @Test
    void 달별_금액은_읽은_순서와_상관없이_오래된_달부터다() {
        List<PerformanceAnalysisResponse.MonthAmount> history = PerformanceAnalysisService.historyOf(List.of(
                new PerformanceAnalyzer.MonthSample(YearMonth.of(2026, 10), 53_000, 1),
                new PerformanceAnalyzer.MonthSample(YearMonth.of(2026, 8), 45_000),
                new PerformanceAnalyzer.MonthSample(YearMonth.of(2026, 9), 52_000)));

        assertThat(history).containsExactly(
                new PerformanceAnalysisResponse.MonthAmount("2026-08", 45_000),
                new PerformanceAnalysisResponse.MonthAmount("2026-09", 52_000),
                new PerformanceAnalysisResponse.MonthAmount("2026-10", 53_000));
    }

    @Test
    void 자연_소비를_세지_않으면_빈_목록이다() {
        assertThat(PerformanceAnalysisService.historyOf(List.of())).isEmpty();
    }
}
