package com.yamu.be.payment.billing.query;

import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.RefundStatus;
import com.yamu.be.payment.billing.query.BillListResponse.UsagePeriod;
import com.yamu.be.payment.billing.query.BillListResponse.WithdrawalAccount;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 청구서 상세 응답(노션 API 명세 V2 「청구서 상세」). 명세에 더해 목록과 같은 {@code withdrawalAccount}, {@code amounts.expectedAmount},
 * {@code amounts.unpaidAmount}를 낸다.
 *
 * <p>{@code amounts.billingDiscount}: 진행 중이면 예상 청구할인(확정 때와 같은 배분으로 계산), 확정 뒤에는 확정 때 회차에 나눈 합계(Task 5.4.7).
 *
 * <p>임시(원천이 아직 없는 값):
 * <ul>
 *   <li>{@code withdrawal.failureReason}·{@code accountBalanceAfter}는 null이고, 미납이면 {@code result}도 null이다.
 *       출금 배치(Task 5.4.3)가 실패 사유를 남기면 채운다.</li>
 *   <li>{@code history}는 상태 이력 표가 없어 청구서에 남은 시각(생성·확정·출금·출금 실패)으로 만든다.
 *       미납에서 취소로 끝난 CANCELED는 바뀐 시각이 없어 넣지 않는다.</li>
 * </ul>
 */
public record BillDetailResponse(Long billId, Long userCardId, String nickname, String maskedNumber,
        YearMonth billingMonth, UsagePeriod usagePeriod, LocalDate closingDate, LocalDate paymentDate,
        LocalDate withdrawalDate, BillStatus status, Amounts amounts, WithdrawalAccount withdrawalAccount,
        List<Item> items, List<Discount> discounts, Withdrawal withdrawal, List<Refund> refunds,
        List<History> history) {

    /**
     * @param itemAmount     청구에 들어간 회차 청구액 합계(확정 전 취소로 빠진 회차만 뺀다). 확정 뒤에는 − billingDiscount = finalAmount
     * @param expectedAmount 진행 중일 때만. 회차 합계 − 예상 청구할인
     * @param finalAmount    확정 뒤에만. 확정 원본 청구액
     * @param unpaidAmount   명세에 더해 낸다. 미납·CANCELED일 때만. 남은 미납액(목록과 같음)
     */
    public record Amounts(long itemAmount, long billingDiscount, Long expectedAmount, Long finalAmount,
            Long unpaidAmount) {
    }

    /**
     * @param seq               이 회차 번호(일시불 1)
     * @param totalSeq          원결제의 전체 회차 수(일시불 1)
     * @param amount            회차 청구액 = 원금 + 수수료
     * @param feeAmount         할부 수수료. 모의 무이자는 0, 미산정이면 null
     * @param installmentPolicy 모의 무이자 할부(POL-013)면 {@code YAMU_INTEREST_FREE}. 일시불·근거 미확인은 null
     */
    public record Item(Long billItemId, Long paymentId, LocalDateTime approvedAt, String merchantName,
            PaymentPlan plan, int seq, int totalSeq, long amount, BillItemStatus status, long principalAmount,
            Long feeAmount, String installmentPolicy) {
    }

    /** 청구 확정 때 확정한 청구할인(혜택별, 이 청구서에 나눈 금액). 진행 중 청구서는 빈 배열이고 예상값은 {@code amounts.billingDiscount}에 있다. */
    public record Discount(Long paymentBenefitId, Long paymentId, String name, long amount,
            LocalDateTime confirmedAt) {
    }

    /**
     * 출금 결과. 출금을 시도하기 전(진행 중·확정)에는 응답에서 null이다.
     *
     * @param result {@code SUCCESS}. 임시: 미납이면 사유를 몰라 null
     */
    public record Withdrawal(LocalDateTime attemptedAt, String result, String failureReason,
            String finTransactionNo, Long accountBalanceAfter) {
    }

    /** @param amount 환급액(원금 + 수수료) */
    public record Refund(Long refundId, Long billItemId, Long paymentId, long amount, RefundStatus status,
            LocalDateTime completedAt) {
    }

    public record History(BillStatus status, LocalDateTime at) {
    }
}
