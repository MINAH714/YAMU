package com.yamu.be.payment.cancel;

import com.yamu.be.payment.cancel.PaymentCancelResponse.BillItemChange;
import com.yamu.be.payment.cancel.PaymentCancelResponse.RefundLine;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 신용카드 취소의 청구 회차 처리 포트(POL-006). 결제 행을 잠근 같은 트랜잭션 안에서 불린다.
 *
 * <p>구현이 맡는 일: 회차가 배정된 청구서를 번호 순으로 잠그고(청구 확정·출금 배치와 겹치지 않게), 회차마다
 * <ul>
 *   <li>청구 전 {@code PENDING} → {@code EXCLUDED}</li>
 *   <li>확정·출금 전(청구서 {@code CONFIRMED}) → {@code REFUND_PENDING}, 기한 없는 환급 대기</li>
 *   <li>출금 완료(청구서 {@code PAID}) → {@code REFUND_PENDING}, 취소일 + 3영업일 기한의 환급 대기</li>
 *   <li>미납(청구서 {@code UNPAID}) → {@code EXCLUDED}, 미납 원금 제외 기록. 환급 없음, 확정 원본 유지</li>
 * </ul>
 * 예외를 던지면 취소까지 함께 롤백된다.
 *
 * <p>구현은 {@link BillItemCreditBillingCanceler}다. 청구 확정(S15P31E103-206) 전이라 지금은 청구 전 회차만 처리하고,
 * 나머지 경우는 실패시킨다.
 */
public interface CreditBillingCanceler {

    Result cancel(PaymentTransaction payment, LocalDateTime canceledAt);

    /** @param billItems 회차별 상태 변화(회차 순), @param refunds 새로 만든 환급 대기 */
    record Result(List<BillItemChange> billItems, List<RefundLine> refunds) {

        public static Result empty() {
            return new Result(List.of(), List.of());
        }
    }
}
