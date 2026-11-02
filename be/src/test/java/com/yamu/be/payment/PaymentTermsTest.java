package com.yamu.be.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** POL-013 검증·수용 기준을 그대로 테스트로 옮겼다. */
class PaymentTermsTest {

    // ───────── 할부 금액 ─────────

    @Test
    void 할부는_50000원이면_되고_49999원이면_INVALID_INSTALLMENT() {
        assertThat(PaymentTerms.installment(50_000, 3).amount()).isEqualTo(50_000);

        assertThatThrownBy(() -> PaymentTerms.installment(49_999, 3))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INSTALLMENT);
    }

    @Test
    void 일시불은_50000원_미만도_된다() {
        assertThat(PaymentTerms.lumpSum(1).amount()).isEqualTo(1);
        assertThat(PaymentTerms.lumpSum(49_999).plan()).isEqualTo(PaymentPlan.LUMP_SUM);
    }

    // ───────── 할부 개월 수 ─────────

    @ParameterizedTest
    @ValueSource(ints = {2, 12})
    void 할부_2개월과_12개월은_된다(int months) {
        assertThat(PaymentTerms.installment(100_000, months).installmentMonths()).isEqualTo(months);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 13})
    void 할부_1개월_이하나_13개월_이상은_INVALID_INSTALLMENT(int months) {
        assertThatThrownBy(() -> PaymentTerms.installment(100_000, months))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INSTALLMENT);
    }

    @Test
    void 할부인데_개월_수가_없으면_INVALID_INSTALLMENT() {
        assertThatThrownBy(() -> new PaymentTerms(100_000, PaymentPlan.INSTALLMENT, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INSTALLMENT);
    }

    @Test
    void 일시불에_개월_수를_보내면_INVALID_INSTALLMENT() {
        assertThatThrownBy(() -> new PaymentTerms(15_000, PaymentPlan.LUMP_SUM, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INSTALLMENT);
    }

    @Test
    void 결제_방식이_없으면_INVALID_INSTALLMENT() {
        assertThatThrownBy(() -> new PaymentTerms(15_000, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INSTALLMENT);
    }

    // ───────── 금액 ─────────

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 금액이_0원이나_음수면_INVALID_AMOUNT(long amount) {
        assertThatThrownBy(() -> PaymentTerms.lumpSum(amount))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void 할부도_금액_오류를_할부_최소_금액보다_먼저_본다() {
        // 0원 할부는 "5만 원 미만"이기도 하지만, 명세는 금액 자체 오류(INVALID_AMOUNT)를 먼저 구분한다
        assertThatThrownBy(() -> PaymentTerms.installment(0, 3))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    // ───────── 카드 종류·내부 표현 ─────────

    @Test
    void 할부는_신용카드만_되고_체크카드는_안_된다() {
        PaymentTerms installment = PaymentTerms.installment(100_000, 3);
        assertThat(installment.allows(CardType.CREDIT)).isTrue();
        assertThat(installment.allows(CardType.CHECK)).isFalse();
    }

    @Test
    void 일시불은_신용_체크_모두_된다() {
        PaymentTerms lumpSum = PaymentTerms.lumpSum(15_000);
        assertThat(lumpSum.allows(CardType.CREDIT)).isTrue();
        assertThat(lumpSum.allows(CardType.CHECK)).isTrue();
    }

    @Test
    void DB에_넣는_개월_수는_일시불_1_할부_총_개월_수() {
        assertThat(PaymentTerms.lumpSum(15_000).installmentMonthsOrOne()).isEqualTo(1);
        assertThat(PaymentTerms.installment(100_000, 3).installmentMonthsOrOne()).isEqualTo(3);
    }

    @Test
    void 결제할_금액은_결제_금액에서_즉시할인을_뺀_값이고_0원_아래로_내려가지_않는다() {
        assertThat(PaymentTerms.lumpSum(15_000).chargedAmount(0)).isEqualTo(15_000);
        assertThat(PaymentTerms.lumpSum(15_000).chargedAmount(750)).isEqualTo(14_250);
        assertThat(PaymentTerms.lumpSum(15_000).chargedAmount(15_000)).isZero();
        assertThat(PaymentTerms.lumpSum(15_000).chargedAmount(20_000)).isZero();
        assertThatThrownBy(() -> PaymentTerms.lumpSum(15_000).chargedAmount(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
