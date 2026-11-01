package com.yamu.be.payment;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;

/**
 * POS가 정한 결제 조건(금액·결제 방식·할부 개월 수). 서버가 검증해 보존하고, 추천·승인·재승인은 모두 이 값을 쓴다.
 * 앱은 이 조건을 바꾸지 못한다(API 명세 V2 「결제 방식·할부 요청 검증 — POL-013 공통 계약」).
 *
 * <p>검증 순서는 명세대로 금액(INVALID_AMOUNT)을 할부 조건(INVALID_INSTALLMENT)보다 먼저 본다.
 * 승인 본문에 plan·installmentMonths 칸이 있으면(값이 null이어도) 거절하는 것은 승인 API의 요청 DTO가 맡는다(Task 5.2.2).
 *
 * @param amount            결제 금액(원). 1원 이상 정수
 * @param plan              결제 방식
 * @param installmentMonths 할부 개월 수(2~12). 일시불은 null
 */
public record PaymentTerms(long amount, PaymentPlan plan, Integer installmentMonths) {

    /** POL-013: 할부 최소 금액. 50,000원은 되고 49,999원은 안 된다. */
    public static final long MIN_INSTALLMENT_AMOUNT = 50_000;
    public static final int MIN_INSTALLMENT_MONTHS = 2;
    public static final int MAX_INSTALLMENT_MONTHS = 12;

    public PaymentTerms {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
        if (plan == null) {
            throw new BusinessException(ErrorCode.INVALID_INSTALLMENT, "결제 방식을 확인할 수 없습니다.");
        }
        switch (plan) {
            case LUMP_SUM -> {
                if (installmentMonths != null) {
                    throw new BusinessException(ErrorCode.INVALID_INSTALLMENT, "일시불에는 할부 개월 수를 보낼 수 없습니다.");
                }
            }
            case INSTALLMENT -> {
                if (installmentMonths == null) {
                    throw new BusinessException(ErrorCode.INVALID_INSTALLMENT, "할부 개월 수를 입력해 주세요.");
                }
                if (installmentMonths < MIN_INSTALLMENT_MONTHS || installmentMonths > MAX_INSTALLMENT_MONTHS) {
                    throw new BusinessException(ErrorCode.INVALID_INSTALLMENT, "할부는 2개월부터 12개월까지만 가능합니다.");
                }
                if (amount < MIN_INSTALLMENT_AMOUNT) {
                    throw new BusinessException(ErrorCode.INVALID_INSTALLMENT, "할부는 50,000원 이상부터 가능합니다.");
                }
            }
        }
    }

    public static PaymentTerms lumpSum(long amount) {
        return new PaymentTerms(amount, PaymentPlan.LUMP_SUM, null);
    }

    public static PaymentTerms installment(long amount, int months) {
        return new PaymentTerms(amount, PaymentPlan.INSTALLMENT, months);
    }

    public boolean isInstallment() {
        return plan == PaymentPlan.INSTALLMENT;
    }

    /** DB {@code payment_transaction.installment_months}에 넣는 값. 일시불은 1, 할부는 총 개월 수. */
    public int installmentMonthsOrOne() {
        return isInstallment() ? installmentMonths : 1;
    }

    /** 이 조건으로 결제할 수 있는 카드 종류인지. 할부는 신용카드만이고 체크카드는 일시불만이다(POL-013). */
    public boolean allows(CardType cardType) {
        return !isInstallment() || cardType == CardType.CREDIT;
    }
}
