package com.yamu.be.payment.approve;

import com.yamu.be.payment.approve.WithdrawalRecovery.LateCheck;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결과 불명으로 PROCESSING에 남은 결제를 주기적으로 복구한다(명세 「결제 상태·예외 처리」 Q5 제안, 임시).
 * 앱이 다시 요청하지 않은 결제도 정리한다.
 *
 * <ul>
 *   <li>한 번 돌 때 대상 전체를 결제 번호 순으로 50건씩 훑는다(최대 {@link #MAX_PER_RUN}건). 판정하지 못한 행(금액 불일치·태그 둘 이상·
 *       계좌 복원 실패·신용카드 행·조회 실패)은 {@code recovery-hold} 동안 건너뛰어, 같은 행이 앞자리를 계속 차지하지 않게 한다.</li>
 *   <li>출금 미확인으로 거절·만료한 결제는 요청 번호 시각 + {@code late-withdrawal-window} 동안 늦은 출금을 다시 대조한다(임시).</li>
 * </ul>
 *
 * <p>{@code yamu.payment.approve.recovery-enabled=true}일 때만 만든다(기본 끔, 테스트는 {@link #run()}을 직접 부른다).
 * {@code updated_at}은 JPA Auditing이 서버 시계로 찍으므로 여기서는 후보를 거르는 데만 쓰고, 판정은 {@link WithdrawalRecovery}가 요청 번호의 시각으로 한다.
 * 보류 표시는 서버 메모리에 둔다. 서버가 여러 대면 서버마다 따로 건너뛴다(잠금이 겹침을 막으므로 판정은 같다).
 *
 * <p>한계: (status, updated_at) 인덱스가 없어 대상 조회가 테이블을 훑는다(스키마 변경은 별도 결정).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yamu.payment.approve", name = "recovery-enabled", havingValue = "true")
public class WithdrawalRecoveryJob {

    static final int PAGE_SIZE = 50;
    static final int MAX_PER_RUN = 500;

    private final PaymentTransactionRepository payments;
    private final WithdrawalRecovery recovery;
    private final PaymentApprovalProperties properties;
    private final Clock clock;
    private final Map<Long, Instant> holdUntil = new ConcurrentHashMap<>();

    public WithdrawalRecoveryJob(PaymentTransactionRepository payments, WithdrawalRecovery recovery,
            PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.recovery = recovery;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${yamu.payment.approve.recovery-interval}")
    public void run() {
        Instant now = clock.instant();
        holdUntil.values().removeIf(until -> !now.isBefore(until));
        recoverProcessing(now);
        recheckDeclined(now);
    }

    private void recoverProcessing(Instant now) {
        LocalDateTime before = LocalDateTime.ofInstant(now.minus(properties.recoveryGrace()), ZoneOffset.UTC);
        int seen = 0;
        long lastId = 0;
        while (seen < MAX_PER_RUN) {
            List<PaymentTransaction> page = payments.findTop50ByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(
                    PaymentStatus.PROCESSING, before, lastId);
            for (PaymentTransaction payment : page) {
                lastId = payment.getId();
                seen++;
                if (isHeld(payment.getId(), now)) {
                    continue;
                }
                try {
                    PaymentStatus after = recovery.recover(payment.getId());
                    log.info("결과 불명 결제 복구 paymentId={} → {}", payment.getId(), after);
                    if (after == PaymentStatus.PROCESSING) {
                        hold(payment.getId(), now);
                    }
                } catch (RuntimeException e) {
                    log.warn("결과 불명 결제 복구 실패, 보류 뒤 다시 paymentId={}", payment.getId(), e);
                    hold(payment.getId(), now);
                }
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
        }
        log.warn("결과 불명 결제가 한 번에 볼 수 있는 수({})를 넘음, 다음 주기에 이어서", MAX_PER_RUN);
    }

    private void recheckDeclined(Instant now) {
        LocalDateTime since = LocalDateTime.ofInstant(
                now.minus(properties.lateWithdrawalWindow()).minus(properties.recoveryGrace()), ZoneOffset.UTC);
        long lastId = 0;
        int seen = 0;
        while (seen < MAX_PER_RUN) {
            List<PaymentTransaction> page = payments
                    .findTop50ByStatusInAndFailureCodeAndUpdatedAtAfterAndIdGreaterThanOrderByIdAsc(
                            List.of(PaymentStatus.DECLINED, PaymentStatus.EXPIRED),
                            PaymentTransaction.WITHDRAWAL_NOT_CONFIRMED, since, lastId);
            for (PaymentTransaction payment : page) {
                lastId = payment.getId();
                seen++;
                try {
                    LateCheck result = recovery.recheckDeclined(payment.getId());
                    if (result == LateCheck.CONFIRMED) {
                        log.warn("늦은 출금 대조로 승인 확정 paymentId={}", payment.getId());
                    }
                } catch (RuntimeException e) {
                    log.warn("늦은 출금 대조 실패, 다음 주기에 다시 paymentId={}", payment.getId(), e);
                }
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    private boolean isHeld(Long paymentId, Instant now) {
        Instant until = holdUntil.get(paymentId);
        return until != null && now.isBefore(until);
    }

    private void hold(Long paymentId, Instant now) {
        holdUntil.put(paymentId, now.plus(properties.recoveryHold()));
    }
}
