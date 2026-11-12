package com.yamu.be.payment.result;

import com.yamu.be.payment.domain.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * POS 결제 내역 응답(S15P31E103-423). POS가 취소할 결제를 고르는 목록이다. 사용자 개인정보(이름·카드 별칭)는 넣지 않는다.
 *
 * @param items 결제 번호 내림차순. 최대 {@link PosPaymentHistoryService#MAX_ITEMS}건
 */
public record PosPaymentHistory(List<Item> items) {

    /**
     * 결제 한 건. 금액·카드 칸의 뜻은 {@link PosPaymentResult}와 같다.
     *
     * @param approvedAt 승인 시각(UTC). 취소된 결제도 원래 승인 시각을 둔다
     * @param canceledAt 취소 시각(UTC). 취소되지 않았으면 null
     * @param cancelable 지금 취소를 요청할 수 있는지(APPROVED). 체크카드 환불 계좌 같은 조건은 취소할 때 다시 본다
     */
    public record Item(
            Long paymentId,
            String orderNo,
            PaymentStatus status,
            String approvalNo,
            LocalDateTime approvedAt,
            LocalDateTime canceledAt,
            long amount,
            Long chargedAmount,
            Long instantDiscount,
            PosPaymentResult.Card card,
            boolean cancelable) {}
}
