package com.yamu.be.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonalDataNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "01012345678, 01012345678",
            "010-1234-5678, 01012345678",
            "+82 10-1234-5678, 01012345678",
            "+82 010-1234-5678, 01012345678",
            "821012345678, 01012345678",
            "011-123-4567, 0111234567"
    })
    void 휴대폰은_숫자만_남기고_국가번호를_0으로_바꾼다(String raw, String expected) {
        assertThat(PersonalDataNormalizer.phone(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0212345678", "010123", "0101234567", "0101234567890", "abc"})
    void 휴대폰_형식이_아니면_400이다(String raw) {
        assertThatThrownBy(() -> PersonalDataNormalizer.phone(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({
            "088, 088-822-4489693615, 088:0888224489693615",
            "004, 0888224489693615, 004:0888224489693615"
    })
    void 계좌_조회키는_은행코드와_숫자만인_계좌번호다(String bank, String raw, String expected) {
        assertThat(PersonalDataNormalizer.accountLookupKey(bank, raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "신한", "088-1", "123456789012345678901"})
    void 은행코드는_영문_숫자_20자_이내여야_한다(String bank) {
        assertThatThrownBy(() -> PersonalDataNormalizer.accountLookupKey(bank, "0888224489693615"))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "9410-1234-1234-5678, 9410123412345678",
            "3712 345678 90123, 371234567890123"
    })
    void 카드번호는_숫자만_남긴다(String raw, String expected) {
        assertThat(PersonalDataNormalizer.cardNo(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9410", "94101234123456781234"})
    void 카드번호_자릿수가_틀리면_400이다(String raw) {
        assertThatThrownBy(() -> PersonalDataNormalizer.cardNo(raw)).isInstanceOf(BusinessException.class);
    }
}
