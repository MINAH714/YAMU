package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 결제일 재변경 가능일(직전 변경 한국 날짜 + 3개월)의 경계를 시계와 상관없이 확인한다. */
class CardManagementRulesTest {

    @Test
    void 바꾼_적이_없으면_제한이_없다() {
        assertThat(CardManagementService.paymentDayChangeableFrom((LocalDateTime) null)).isNull();
    }

    @Test
    void 한국_날짜_기준으로_센다() {
        // UTC 10/31 15:30 = 한국 11/1 00:30 → 2/1부터
        assertThat(CardManagementService.paymentDayChangeableFrom(LocalDateTime.of(2026, 10, 31, 15, 30)))
                .isEqualTo(LocalDate.of(2027, 2, 1));
        // UTC 10/31 14:59 = 한국 10/31 23:59 → 1/31부터
        assertThat(CardManagementService.paymentDayChangeableFrom(LocalDateTime.of(2026, 10, 31, 14, 59)))
                .isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    void 말일에_바꾸면_3개월_뒤_같은_날이_없을_때_그달_말일이다() {
        // 한국 11/30 → 2/30이 없어 2/28
        assertThat(CardManagementService.paymentDayChangeableFrom(LocalDateTime.of(2026, 11, 30, 3, 0)))
                .isEqualTo(LocalDate.of(2027, 2, 28));
        // 윤년이면 2/29
        assertThat(CardManagementService.paymentDayChangeableFrom(LocalDateTime.of(2027, 11, 30, 3, 0)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
    }
}
