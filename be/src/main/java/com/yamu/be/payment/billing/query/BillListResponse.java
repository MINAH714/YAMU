package com.yamu.be.payment.billing.query;

import com.yamu.be.payment.billing.BillStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 청구서 목록 응답(노션 API 명세 V2 「청구서 목록」).
 *
 * <p>{@code billingDiscount}: 진행 중이면 예상 청구할인(확정 때와 같은 배분으로 계산, 저장하지 않음), 확정 뒤에는 확정 때 회차에 나눈 합계다
 * (Task 5.4.7). 예상 청구액 = 청구 전 회차 합계 − 예상 청구할인.
 */
public record BillListResponse(List<Item> items, Summary summary) {

    /**
     * @param billingMonth   결제일이 속한 달
     * @param paymentDate    결제일(영업일 보정 전) = 이용기간 마감일 + 상품의 마감 일수
     * @param withdrawalDate 출금 예정일. 결제일이 비영업일이면 다음 영업일
     * @param itemCount      청구에 들어간 회차 수. 확정 전 취소로 빠진 회차만 빼고, 확정 뒤 취소한 회차(미납 제외 포함)는 센다
     * @param itemAmount     청구에 들어간 회차 청구액 합계. 확정 뒤에는 itemAmount − billingDiscount = finalAmount
     * @param expectedAmount 진행 중(ACCUMULATING)일 때만. 지금까지 쌓인 회차 합계 − 예상 청구할인
     * @param finalAmount    확정 뒤에만. 확정 원본 청구액(확정 뒤 취소는 이 값을 바꾸지 않고 환급·미납 제외로 처리)
     * @param unpaidAmount   명세에 더해 낸다. 미납(UNPAID)·CANCELED일 때만. 남은 미납액 = finalAmount − 출금액 − 취소로 뺀 미납 원금
     *                       (잔고예보의 미납 금액과 같다). CANCELED는 0
     * @param withdrawalAccount 출금 계좌. 진행 중이면 카드의 지금 계좌, 확정 뒤에는 확정 당시 계좌. 카드에 계좌가 없으면 null
     */
    public record Item(Long billId, Long userCardId, String nickname, YearMonth billingMonth, UsagePeriod usagePeriod,
            LocalDate closingDate, LocalDate paymentDate, LocalDate withdrawalDate, BillStatus status, int itemCount,
            long itemAmount, long billingDiscount, Long expectedAmount, Long finalAmount, Long unpaidAmount,
            LocalDateTime confirmedAt, LocalDateTime paidAt, WithdrawalAccount withdrawalAccount) {
    }

    /** 이용기간(양 끝 포함, 한국 날짜) */
    public record UsagePeriod(LocalDate from, LocalDate to) {
    }

    /** @param maskedAccountNo 끝 4자리만 보이는 계좌번호(다른 계좌 API와 같은 형식) */
    public record WithdrawalAccount(Long accountId, String bankName, String maskedAccountNo) {
    }

    /**
     * 목록의 상태·월 필터와 상관없이 끝나지 않은 청구서로 계산한다(카드 필터는 따른다).
     *
     * @param nextWithdrawal 출금일이 가장 빠른 진행 중·확정 청구서. 없으면 null
     * @param unpaidCount    미납(UNPAID) 청구서 수
     */
    public record Summary(NextWithdrawal nextWithdrawal, int unpaidCount) {
    }

    /** @param expectedAmount 진행 중이면 예상 청구액, 확정이면 확정 청구액 */
    public record NextWithdrawal(Long billId, LocalDate date, long expectedAmount) {
    }
}
