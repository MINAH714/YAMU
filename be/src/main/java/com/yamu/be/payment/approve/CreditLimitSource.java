package com.yamu.be.payment.approve;

/**
 * 모의 가용 신용한도 포트(POL-013: 전체 원금이 가용 한도 이내여야 승인). 초기값·저장 단위·차감·복원은 후속 설계라
 * 구현이 없으면 {@link UnlimitedCreditLimitSource}(검사 생략)가 쓰인다.
 *
 * <p>구현이 생기면: 한도를 모르면 {@link Result#UNKNOWN}(승인 전 422 DATA_INSUFFICIENT), 전체 원금이 가용 한도를 넘으면
 * {@link Result#EXCEEDED}(200 DECLINED CREDIT_LIMIT_EXCEEDED). 월 납부액이 아니라 전체 원금으로 본다.
 */
public interface CreditLimitSource {

    /** @param principal 전체 원금(원) */
    LimitCheck check(Long userId, Long userCardId, long principal);

    enum Result {
        /** 한도를 보지 않는다(임시 구현) */
        SKIPPED,
        SUFFICIENT,
        EXCEEDED,
        /** 한도 자료가 없어 판정할 수 없다 */
        UNKNOWN
    }

    /** @param availableLimit 가용 한도(원). 모르거나 검사하지 않으면 null */
    record LimitCheck(Result result, Long availableLimit) {

        public static LimitCheck skipped() {
            return new LimitCheck(Result.SKIPPED, null);
        }

        public static LimitCheck sufficient(long availableLimit) {
            return new LimitCheck(Result.SUFFICIENT, availableLimit);
        }

        public static LimitCheck exceeded(long availableLimit) {
            return new LimitCheck(Result.EXCEEDED, availableLimit);
        }

        public static LimitCheck unknown() {
            return new LimitCheck(Result.UNKNOWN, null);
        }
    }
}
