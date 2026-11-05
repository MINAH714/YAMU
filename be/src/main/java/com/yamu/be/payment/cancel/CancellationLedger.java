package com.yamu.be.payment.cancel;

import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDateTime;

/**
 * 취소 원장 복구 포트(Task 5.2.5와 짝). 결제 행을 CANCELED로 바꾼 같은 트랜잭션 안에서 불린다.
 *
 * <p>구현이 맡는 일(POL-006, FR-026·FR-060): 승인 당시 저장한 실적 인정액·귀속 월로 {@code card_monthly_performance}를 빼고
 * {@code performance_reversed_at}을 적는다. {@code payment_limit_usage}의 {@code consumed_value}만큼 {@code monthly_limit_usage}를
 * 되돌리고 {@code restored_at}을 적는다. 이미 시각이 있으면 다시 빼지 않는다. 예외를 던지면 취소까지 함께 롤백된다.
 * {@code payment_benefit}은 지우지 않는다(취소 여부는 결제 상태로 본다).
 *
 * <p>구현이 없으면 {@link NoopCancellationLedger}가 쓰인다. 승인 쪽도 아직 아무것도 적지 않으므로(Noop) 되돌릴 것이 없다.
 */
public interface CancellationLedger {

    PaymentCancelResponse.Restored reverse(PaymentTransaction payment, LocalDateTime canceledAt);
}
