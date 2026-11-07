package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** 승인이 저장할 실적 인정액·실적 월(Task 5.2.11). */
class ApprovalPerformanceTest {

    /** 한국 시각 2026-11-01 00:30. UTC로는 10월 31일이다. */
    private static final LocalDateTime NOV_1_KST_0030 = LocalDateTime.of(2026, 10, 31, 15, 30);
    /** 한국 시각 2026-11-01 00:29. 승인과 같은 달에 요청한 경우. */
    private static final LocalDateTime NOV_1_KST_0029 = LocalDateTime.of(2026, 10, 31, 15, 29);
    /** 한국 시각 2026-10-31 23:59:40. 승인 대기(60초) 중에 달이 바뀐다. */
    private static final LocalDateTime OCT_31_KST_235940 = LocalDateTime.of(2026, 10, 31, 14, 59, 40);
    /** 한국 시각 2026-11-01 00:00:20. */
    private static final LocalDateTime NOV_1_KST_000020 = LocalDateTime.of(2026, 10, 31, 15, 0, 20);

    @Test
    void BE3_인정_값을_그대로_쓴다() {
        var recognition = new PerformanceRecognition(1L, YearMonth.of(2026, 12), 10_000L);

        assertThat(ApprovalPerformance.amount(recognition, true)).isEqualTo(10_000L);
        assertThat(ApprovalPerformance.amount(recognition, false)).isEqualTo(10_000L);
        assertThat(ApprovalPerformance.month(recognition, NOV_1_KST_0029, NOV_1_KST_0030))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void 혜택을_적용한_승인의_0은_제외_확정으로_남긴다() {
        var excluded = new PerformanceRecognition(1L, YearMonth.of(2026, 11), 0L);

        assertThat(ApprovalPerformance.amount(excluded, true)).isZero();
    }

    @Test
    void 혜택_없이_승인했는데_BE3가_0을_내면_미확인이다() {
        // 할인 제외 상품은 혜택 계산을 전제로 0을 낸다. 혜택을 받지 않은 승인에는 그 0이 맞는지 가릴 수 없다
        var excluded = new PerformanceRecognition(1L, YearMonth.of(2026, 11), 0L);

        assertThat(ApprovalPerformance.amount(excluded, false)).isNull();
    }

    @Test
    void 인정_값이_없거나_인정액이_미확인이면_null이다() {
        assertThat(ApprovalPerformance.amount(null, true)).isNull();
        assertThat(ApprovalPerformance.amount(new PerformanceRecognition(1L, null, null), true)).isNull();
    }

    @Test
    void BE3가_인정월을_정하지_않으면_승인_시각의_한국_월이다() {
        assertThat(ApprovalPerformance.month(null, NOV_1_KST_0029, NOV_1_KST_0030)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(ApprovalPerformance.month(new PerformanceRecognition(1L, null, null), NOV_1_KST_0029, NOV_1_KST_0030))
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void 월말에_요청하고_다음_달에_승인하면_인정월_규칙이_있어도_승인월이다() {
        // BE3는 요청 시각(10월)으로 인정월을 정한다. 인정월 규칙이 없는 카드(승인월 11월)와 같은 달에 넣는다
        var sameMonth = new PerformanceRecognition(1L, YearMonth.of(2026, 10), 10_000L);

        assertThat(ApprovalPerformance.month(sameMonth, OCT_31_KST_235940, NOV_1_KST_000020))
                .isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(ApprovalPerformance.month(null, OCT_31_KST_235940, NOV_1_KST_000020))
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void BE3가_요청월에서_민_만큼만_승인월에서_민다() {
        // 후불교통처럼 다음 달 실적이면, 요청 10월 → BE3 11월이므로 승인 11월 → 12월이다
        var nextMonth = new PerformanceRecognition(1L, YearMonth.of(2026, 11), 10_000L);

        assertThat(ApprovalPerformance.month(nextMonth, OCT_31_KST_235940, NOV_1_KST_000020))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void 요청_시각을_모르면_BE3_인정월을_그대로_쓴다() {
        var recognition = new PerformanceRecognition(1L, YearMonth.of(2026, 10), 10_000L);

        assertThat(ApprovalPerformance.month(recognition, null, NOV_1_KST_000020)).isEqualTo(LocalDate.of(2026, 10, 1));
    }
}
