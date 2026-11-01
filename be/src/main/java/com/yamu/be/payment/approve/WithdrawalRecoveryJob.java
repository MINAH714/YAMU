package com.yamu.be.payment.approve;

import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결과 불명으로 PROCESSING에 남은 결제를 주기적으로 복구한다(명세 「결제 상태·예외 처리」 Q5 제안, 임시).
 * 앱이 다시 요청하지 않은 결제도 정리한다. 한 번에 오래된 것부터 50건.
 *
 * <p>{@code yamu.payment.approve.recovery-enabled=false}면 만들지 않는다(테스트는 {@link #run()}을 직접 부른다).
 * {@code updated_at}은 JPA Auditing이 서버 시계로 찍으므로 여기서는 후보를 거르는 데만 쓰고, 판정은 {@link WithdrawalRecovery}가 요청 번호의 시각으로 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yamu.payment.approve", name = "recovery-enabled", havingValue = "true")
public class WithdrawalRecoveryJob {

    private final PaymentTransactionRepository payments;
    private final WithdrawalRecovery recovery;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public WithdrawalRecoveryJob(PaymentTransactionRepository payments, WithdrawalRecovery recovery,
            PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.recovery = recovery;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${yamu.payment.approve.recovery-interval}")
    public void run() {
        LocalDateTime before = LocalDateTime.ofInstant(clock.instant().minus(properties.recoveryGrace()),
                ZoneOffset.UTC);
        List<PaymentTransaction> stuck = payments.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                PaymentStatus.PROCESSING, before);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("결과 불명 결제 복구 시작 count={}", stuck.size());
        for (PaymentTransaction payment : stuck) {
            try {
                PaymentStatus after = recovery.recover(payment.getId());
                log.info("결과 불명 결제 복구 paymentId={} → {}", payment.getId(), after);
            } catch (RuntimeException e) {
                log.warn("결과 불명 결제 복구 실패, 다음 주기에 다시 paymentId={}", payment.getId(), e);
            }
        }
    }
}
