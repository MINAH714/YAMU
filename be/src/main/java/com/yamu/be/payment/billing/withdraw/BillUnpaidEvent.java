package com.yamu.be.payment.billing.withdraw;

import com.yamu.be.payment.bank.TransferResult;

/**
 * 은행이 청구 출금을 거절해 청구서가 미납(UNPAID)이 됐다(거래 알림의 미납). 알림 모듈이 아직 없어 이벤트만
 * 발행한다(2026-11-05 결정). 거절 사유는 DB에 저장하지 않고(칸 없음) 알림 문구용으로 이벤트에만 싣는다.
 * 출금 결과 저장 트랜잭션 안에서 발행하므로, 받는 쪽은 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다.
 *
 * <p>출금 전에 청구서의 결제가 모두 취소돼 남은 미납액이 0원이 된 경우(청구서 CANCELED)는 낼 돈이 없어 발행하지 않는다.
 *
 * @param unpaidAmount 남은 미납액(확정액 − 출금 전 취소로 뺀 몫)
 * @param reason       거절 사유(잔액 부족·계좌 없음·그 밖)
 */
public record BillUnpaidEvent(Long cardBillId, Long userId, Long userCardId, long unpaidAmount,
        TransferResult.RejectReason reason) {
}
