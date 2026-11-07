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

    @Test
    void BE3_인정_값을_그대로_쓴다() {
        var recognition = new PerformanceRecognition(1L, YearMonth.of(2026, 12), 10_000L);

        assertThat(ApprovalPerformance.amount(recognition, true)).isEqualTo(10_000L);
        assertThat(ApprovalPerformance.amount(recognition, false)).isEqualTo(10_000L);
        assertThat(ApprovalPerformance.month(recognition, NOV_1_KST_0030)).isEqualTo(LocalDate.of(2026, 12, 1));
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
        assertThat(ApprovalPerformance.month(null, NOV_1_KST_0030)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(ApprovalPerformance.month(new PerformanceRecognition(1L, null, null), NOV_1_KST_0030))
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }
}
