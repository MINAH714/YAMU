package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ApprovalNumbersTest {

    @Test
    void Y_한국_승인일_8자리_결제번호_10자리다() {
        // 2026-10-22T05:31:05Z = 한국 14:31
        assertThat(ApprovalNumbers.of(LocalDateTime.of(2026, 10, 22, 5, 31, 5), 1024L))
                .isEqualTo("Y202610220000001024")
                .hasSize(19);
    }

    @Test
    void 날짜는_UTC가_아니라_한국_날짜다() {
        // UTC 10/22 15:30 = 한국 10/23 00:30
        assertThat(ApprovalNumbers.of(LocalDateTime.of(2026, 10, 22, 15, 30), 7L)).startsWith("Y20261023");
        // UTC 10/22 14:59 = 한국 10/22 23:59
        assertThat(ApprovalNumbers.of(LocalDateTime.of(2026, 10, 22, 14, 59), 7L)).startsWith("Y20261022");
    }

    @Test
    void 결제_번호가_커도_10자리를_넘지_않는_한_그대로_쓴다() {
        assertThat(ApprovalNumbers.of(LocalDateTime.of(2026, 1, 1, 0, 0), 9_999_999_999L))
                .isEqualTo("Y202601019999999999");
    }
}
