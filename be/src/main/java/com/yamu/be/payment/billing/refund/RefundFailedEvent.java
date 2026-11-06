package com.yamu.be.payment.billing.refund;

import com.yamu.be.payment.bank.TransferResult.RejectReason;

/**
 * 환급 계좌가 없어(A1003) 은행이 신용카드 취소 환급 입금을 거절해 환급이 실패(FAILED)로 끝났다(Task 5.4.4). 자동으로 다시
 * 보내지 않는다. 그 밖의 거절 코드는 환급을 대기로 두므로 이 이벤트가 나가지 않는다.
 * 알림·운영 처리는 담당 모듈이 이 이벤트를 받아 한다. 아직 받는 곳은 없다. 발행 시점은 {@link RefundCompletedEvent}와 같다.
 *
 * @param amount 보내려던 금액(원)
 * @param reason 거절 사유(지금은 항상 {@code ACCOUNT_NOT_FOUND}). 금융망 코드는 넣지 않는다(운영 로그에만 남긴다)
 */
public record RefundFailedEvent(Long refundId, Long userId, Long paymentTransactionId, long amount,
        RejectReason reason) {
}
