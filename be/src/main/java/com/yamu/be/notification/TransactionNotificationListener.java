package com.yamu.be.notification;

import com.yamu.be.payment.billing.confirm.BillConfirmedEvent;
import com.yamu.be.payment.billing.refund.RefundCompletedEvent;
import com.yamu.be.payment.billing.withdraw.BillUnpaidEvent;
import com.yamu.be.payment.billing.withdraw.BillWithdrawnEvent;
import com.yamu.be.payment.domain.PaymentApprovedEvent;
import com.yamu.be.payment.domain.PaymentCanceledEvent;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 거래 알림(Task 9.3.2): 결제 승인·취소, 청구 확정, 출금 완료, 미납, 환급 입금. 결제·청구 모듈이 발행한 사건을 커밋 뒤에 받아 쌓는다.
 *
 * <ul>
 *   <li>커밋 뒤({@code AFTER_COMMIT}): 롤백된 승인·확정·출금은 알리지 않는다.</li>
 *   <li>새 트랜잭션({@code REQUIRES_NEW}): 커밋 뒤 콜백에서는 원래 트랜잭션의 연결이 아직 묶여 있어, 그냥 쓰면 커밋되지 않는다(Spring
 *       {@code TransactionSynchronization#afterCommit} 문서).</li>
 *   <li>실패 격리: 알림을 못 쌓아도(설정 저장값 이상·DB 오류) 예외를 밖으로 던지지 않고 경고 로그만 남긴다. 커밋 뒤 콜백의 예외는
 *       결제·청구 API 호출자에게 전해져, 이미 끝난 승인을 실패로 보이게 하기 때문이다. 한계: 커밋과 알림 저장 사이에 서버가 꺼지면
 *       그 알림은 쌓이지 않는다(다시 만드는 작업 없음, 임시).</li>
 *   <li>중복: 같은 사건을 두 번 받아도 {@link NotificationWriter}의 중복 방지 키로 한 번만 쌓인다.</li>
 * </ul>
 *
 * <p>결제 결과 알림은 승인·취소에서만 만든다(2026-11-10 결정). 거절(DECLINED)은 다른 카드로 다시 시도할 수 있는 중간 상태이고
 * 결제 화면에서 바로 보이므로 쌓지 않는다.
 */
@Slf4j
@Component
class TransactionNotificationListener {

    private final NotificationWriter writer;
    private final NotificationContentReader contents;
    private final TransactionTemplate requiresNew;

    TransactionNotificationListener(NotificationWriter writer, NotificationContentReader contents,
            PlatformTransactionManager transactionManager) {
        this.writer = writer;
        this.contents = contents;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(PaymentApprovedEvent event) {
        notify("PAYMENT_APPROVED", event.paymentId(), event.userId(), () -> contents
                .payment(event.userId(), event.paymentId())
                .map(payment -> NotificationMessages.paymentApproved(event.userId(), event.paymentId(), payment)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(PaymentCanceledEvent event) {
        notify("PAYMENT_CANCELED", event.paymentId(), event.userId(), () -> contents
                .payment(event.userId(), event.paymentId())
                .map(payment -> NotificationMessages.paymentCanceled(event.userId(), event.paymentId(), payment)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(BillConfirmedEvent event) {
        notify("BILL_CONFIRMED", event.cardBillId(), event.userId(), () -> contents
                .bill(event.userId(), event.cardBillId())
                .map(bill -> NotificationMessages.billConfirmed(event.userId(), event.cardBillId(), bill,
                        event.confirmedAmount(), event.withdrawalDate(), event.settledWithoutWithdrawal())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(BillWithdrawnEvent event) {
        notify("BILL_WITHDRAWN", event.cardBillId(), event.userId(), () -> contents
                .bill(event.userId(), event.cardBillId())
                .map(bill -> NotificationMessages.billWithdrawn(event.userId(), event.cardBillId(), bill,
                        event.amount(), event.withdrawnOn())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(BillUnpaidEvent event) {
        notify("BILL_UNPAID", event.cardBillId(), event.userId(), () -> contents
                .bill(event.userId(), event.cardBillId())
                .map(bill -> NotificationMessages.billUnpaid(event.userId(), event.cardBillId(), bill,
                        event.unpaidAmount(), event.reason() == null ? "" : event.reason().name())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RefundCompletedEvent event) {
        notify("REFUND_COMPLETED", event.refundId(), event.userId(), () -> Optional.of(
                NotificationMessages.refundCompleted(event.userId(), event.refundId(), event.paymentTransactionId(),
                        contents.payment(event.userId(), event.paymentTransactionId()).orElse(null),
                        event.amount())));
    }

    /**
     * 새 트랜잭션에서 문구를 읽고 쌓는다. 어떤 실패도 밖으로 던지지 않는다.
     *
     * @param draft 문구 재료를 읽어 알림을 만든다. 대상 행이 없으면 empty
     */
    private void notify(String event, Long refId, Long userId, Supplier<Optional<NotificationWriter.Draft>> draft) {
        try {
            NotificationWriter.Result result = requiresNew.execute(status -> draft.get()
                    .map(writer::write)
                    .orElse(null));
            if (result == null) {
                log.warn("알림 대상 행이 없어 알림을 만들지 않음 event={} refId={} userId={}", event, refId, userId);
            } else {
                log.debug("알림 처리 event={} refId={} userId={} result={}", event, refId, userId, result);
            }
        } catch (RuntimeException e) {
            log.warn("알림을 쌓지 못함(원래 처리는 완료) event={} refId={} userId={}", event, refId, userId, e);
        }
    }
}
