package com.yamu.be.payment.cancel;

import com.yamu.be.payment.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 취소 응답(API 명세 「결제 취소」 Response). 신용카드는 회차별 결과와 환급 대기를, 체크카드는 입금 뒤 잔액을 담는다.
 *
 * @param restored            이번 취소로 복구한 실적·한도. 원장 반영(Task 5.2.5) 전에는 비어 있다
 * @param billItems           신용카드 회차별 상태 변화(회차 순). 체크카드는 빈 목록
 * @param refunds             이번 취소로 만든 환급 대기. 출금 전·미납·청구 전 회차는 만들지 않는다
 * @param accountBalanceAfter 체크카드 환불 입금 뒤 잔액(Task 5.3.3). 신용카드는 null
 */
public record PaymentCancelResponse(
        Long paymentId,
        PaymentStatus status,
        LocalDateTime canceledAt,
        Restored restored,
        List<BillItemChange> billItems,
        List<RefundLine> refunds,
        Long accountBalanceAfter) {

    /**
     * @param performance  승인 당시 실적 인정액·귀속 월로 차감한 실적. 복구할 것이 없으면 null
     * @param limitImpacts 한도 그룹별 복구량. 실제 환급액·청구할인과 같은 값이 아니다
     */
    public record Restored(Performance performance, List<LimitImpact> limitImpacts) {

        /** 아무것도 복구하지 않음(원장 반영 전). */
        public static Restored none() {
            return new Restored(null, List.of());
        }
    }

    /** @param yearMonth 실적 월(한국 기준, {@code 2026-10}) */
    public record Performance(String yearMonth, long amount) {}

    /** @param period PER_TRANSACTION / DAILY / MONTHLY */
    public record LimitImpact(Long limitGroupId, String period, String yearMonth, long restoredAmount) {}

    /**
     * @param seq    회차 번호(일시불 1)
     * @param amount 회차 청구액
     * @param before 취소 전 회차 상태
     * @param after  취소 뒤 회차 상태
     */
    public record BillItemChange(Long billItemId, int seq, long amount, String before, String after) {}

    /**
     * @param dueDate     출금이 끝난 회차는 기한(한국 날짜), 출금 전 회차는 null(출금 성공 뒤 정함)
     * @param toAccountId 환급 계좌
     */
    public record RefundLine(Long refundId, Long billItemId, long amount,
            @Schema(example = "PENDING") String status, LocalDate dueDate, Long toAccountId) {}
}
