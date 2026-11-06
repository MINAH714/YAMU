package com.yamu.be.payment.billing.withdraw;

import java.time.LocalDate;

/**
 * 청구서 출금이 성공했다(거래 알림의 출금 완료). 알림 모듈이 아직 없어 이벤트만 발행한다(2026-11-05 결정).
 * 출금 결과 저장 트랜잭션 안에서 발행하므로, 받는 쪽은 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다.
 *
 * @param amount           출금액(= 확정 청구액)
 * @param withdrawnOn      금융망이 찍은 출금일(한국 날짜)
 * @param finTransactionNo 금융망 출금 거래 번호
 */
public record BillWithdrawnEvent(Long cardBillId, Long userId, Long userCardId, long amount, LocalDate withdrawnOn,
        String finTransactionNo) {
}
