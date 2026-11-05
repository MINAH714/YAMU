package com.yamu.be.payment.billing.confirm;

import java.time.LocalDate;

/**
 * 청구서가 확정됐다(API 명세 알림 {@code BILL_CONFIRMED}). 알림 모듈이 아직 없어 이벤트만 발행한다(2026-11-05 결정).
 * 확정 트랜잭션 안에서 발행하므로, 받는 쪽은 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받아야
 * 롤백된 확정을 알리지 않는다.
 *
 * @param confirmedAmount 확정 청구액. 0원이면 출금 없이 바로 닫힌 청구서다({@code settledWithoutWithdrawal})
 * @param withdrawalDate  영업일로 보정한 출금 예정일(한국 날짜)
 */
public record BillConfirmedEvent(Long cardBillId, Long userId, Long userCardId, long confirmedAmount,
        LocalDate withdrawalDate, boolean settledWithoutWithdrawal) {
}
