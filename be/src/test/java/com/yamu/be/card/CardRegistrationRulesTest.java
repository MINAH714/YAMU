package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.dto.CardLookupRequest;
import com.yamu.be.card.dto.CardRegisterRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** DB 없이 확인하는 카드 확인·등록 규칙(유효기간 경계, 카드번호 마스킹, 별칭 정리). */
class CardRegistrationRulesTest {

    @Test
    void 유효기간은_그_달_말일까지_유효하고_한국_날짜로_달이_바뀌면_지난_달이_된다() {
        // UTC 10/31 15:30 = 한국 11/1 00:30
        Clock kstNovember = Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC);

        assertThat(CardRegistrationService.isExpired(YearMonth.of(2026, 10), kstNovember)).isTrue();
        assertThat(CardRegistrationService.isExpired(YearMonth.of(2026, 11), kstNovember)).isFalse();

        // UTC 10/31 14:59 = 한국 10/31 23:59 → 10월 카드는 아직 쓸 수 있다
        Clock kstOctoberLast = Clock.fixed(Instant.parse("2026-10-31T14:59:00Z"), ZoneOffset.UTC);
        assertThat(CardRegistrationService.isExpired(YearMonth.of(2026, 10), kstOctoberLast)).isFalse();
        assertThat(CardRegistrationService.isExpired(YearMonth.of(2026, 9), kstOctoberLast)).isTrue();
    }

    @Test
    void 카드_확인_요청의_toString에는_카드번호_원문이_없다() {
        CardLookupRequest request = new CardLookupRequest("9410123456785678", "12", "29");

        assertThat(request.toString()).doesNotContain("9410123456785678").contains("5678");
    }

    @Test
    void 등록_요청의_별칭은_앞뒤_공백을_빼고_비면_null이다() {
        assertThat(new CardRegisterRequest("cvt_x", "  생활비 카드 ", null, null, null).nickname()).isEqualTo("생활비 카드");
        assertThat(new CardRegisterRequest("cvt_x", "   ", null, null, null).nickname()).isNull();
        assertThat(new CardRegisterRequest("cvt_x", null, null, null, null).nickname()).isNull();
    }
}
