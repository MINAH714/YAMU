package com.yamu.be.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonalDataMaskingConverterTest {

    @Test
    void 휴대폰은_가운데를_가린다() {
        assertThat(PersonalDataMaskingConverter.mask("번호 010-1234-5678 확인")).isEqualTo("번호 010-****-5678 확인");
        assertThat(PersonalDataMaskingConverter.mask("phone=01012345678,")).isEqualTo("phone=010-****-5678,");
        assertThat(PersonalDataMaskingConverter.mask("011-123-4567")).isEqualTo("011-****-4567");
    }

    @Test
    void 카드_계좌_결제코드처럼_15에서_16자리_숫자는_끝_4자리만_남긴다() {
        assertThat(PersonalDataMaskingConverter.mask("card 1234567812345678 ok")).isEqualTo("card ************5678 ok");
        assertThat(PersonalDataMaskingConverter.mask("card 1234-5678-1234-5678")).isEqualTo("card ************5678");
        assertThat(PersonalDataMaskingConverter.mask("card 1234 5678 1234 5678")).isEqualTo("card ************5678");
        assertThat(PersonalDataMaskingConverter.mask("amex 378282246310005")).isEqualTo("amex ***********0005");
        assertThat(PersonalDataMaskingConverter.mask("a=0016174648358792 b=0016174648358793"))
                .isEqualTo("a=************8792 b=************8793");
    }

    @Test
    void 시각_거래번호_금액_ID는_그대로다() {
        String[] untouched = {
                "tx=20261109150000123456 http=200",   // 거래 고유번호(20자리)
                "at 20261109150000",                  // yyyyMMddHHmmss(14자리)
                "잔액 1500004원 userId=42 accountId=7",
                "2026-11-09T06:00:00Z",
                "",
        };
        for (String text : untouched) {
            assertThat(PersonalDataMaskingConverter.mask(text)).isEqualTo(text);
        }
        assertThat(PersonalDataMaskingConverter.mask(null)).isNull();
    }
}
