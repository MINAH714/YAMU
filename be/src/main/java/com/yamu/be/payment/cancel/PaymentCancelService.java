package com.yamu.be.payment.cancel;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.IdempotencyKeys;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.approve.WithdrawalRecovery;
import com.yamu.be.payment.cancel.CancelIdempotencyStore.Previous;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.domain.TransactionSource;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 전체 취소(API 명세 「결제 취소」, POL-006, FR-068). 신용카드(Task 5.3.2)와 체크카드(Task 5.3.3)를 처리한다.
 *
 * <pre>
 * ① Idempotency-Key(UUID)·본문 계약(취소 금액 금지) → 요청 지문
 * ② 멱등: 같은 키 재전송은 처음 응답, 다른 내용은 409, 처리 중이면 409
 * ③ 결제 읽기(남의 결제 404) → 상태·출처 검사 → 카드 종류로 나눔
 * ④ 신용카드, 한 트랜잭션: 결제 행 잠금 → 다시 검사 → CANCELED → 회차 처리(CreditBillingCanceler)
 *    → 실적·한도 복구(CancellationLedger)
 * ⑤ 체크카드: 결제별 정산 잠금 → 다시 검사 → 환불 입금(CheckCardRefunder, DB 트랜잭션 밖)
 *    → 한 트랜잭션: 결제 행 잠금 → 다시 검사 → CANCELED → 실적·한도 복구
 * </pre>
 *
 * <p>깨질 수 있는 곳: 신용카드의 연타·두 기기(다른 키)는 ④의 행 잠금으로 줄을 서고, 뒤 요청은 CANCELED를 읽어 409 ALREADY_CANCELED다.
 * 그래서 회차 처리·환급 행·실적 복구가 한 번만 일어난다(환급은 {@code uk_refund_item}도 막는다). 같은 키 재전송은 ②의 Redis 응답과 잠금.
 * 회차 처리나 복구가 실패하면 결제도 APPROVED로 남는다(부분 반영 없음).
 *
 * <p>체크카드는 돈이 먼저 움직이므로 DB 행 잠금 대신 승인·복구와 같은 정산 잠금으로 한 줄로 세운다(다른 키의 동시 취소는 409 처리 중).
 * 입금 뒤 저장이 실패하면 결제는 APPROVED로 남고, 다시 취소하면 거래내역의 환불 태그를 찾아 입금 없이 CANCELED가 된다.
 */
@Slf4j
@Service
public class PaymentCancelService {

    private final PaymentTransactionRepository payments;
    private final PaymentCardLookup cards;
    private final CreditBillingCanceler billing;
    private final CheckCardRefunder refunder;
    private final CancellationLedger ledger;
    private final CancelIdempotencyStore idempotency;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public PaymentCancelService(PaymentTransactionRepository payments, PaymentCardLookup cards,
            CreditBillingCanceler billing, CheckCardRefunder refunder, CancellationLedger ledger,
            CancelIdempotencyStore idempotency, RedisLocks locks, TransactionTemplate transaction,
            PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.cards = cards;
        this.billing = billing;
        this.refunder = refunder;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.locks = locks;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    public PaymentCancelResponse cancel(AuthUser me, Long paymentId, PaymentCancelRequest request,
            String idempotencyKey) {
        Long userId = me.userId();
        String key = IdempotencyKeys.require(idempotencyKey);
        String fingerprint = fingerprint(paymentId, request);

        Optional<Previous> previous = idempotency.find(userId, key);
        if (previous.isPresent()) {
            return replay(previous.get(), fingerprint);
        }
        Optional<RedisLocks.Lock> lock = locks.tryLock(idempotency.lockKey(userId, key),
                properties.idempotencyLockTtl());
        if (lock.isEmpty()) {
            return idempotency.find(userId, key)
                    .map(done -> replay(done, fingerprint))
                    .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                            "결제 취소를 처리하고 있습니다. 잠시 후 결과를 확인해 주세요."));
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            Optional<Previous> done = idempotency.find(userId, key);
            if (done.isPresent()) {
                return replay(done.get(), fingerprint);
            }
            PaymentCancelResponse response = process(userId, paymentId);
            remember(userId, key, new Previous(fingerprint, paymentId, response));
            return response;
        }
    }

    /**
     * 응답 기억. 취소는 이미 커밋됐으므로 실패해도 응답은 그대로 돌려준다(승인과 같다). 예외를 던지면 앱은 실제로 취소된 결제를
     * 실패로 보게 된다. 한계: 기억하지 못한 키로 다시 보내면 처음 응답 대신 409 ALREADY_CANCELED를 받는다.
     */
    private void remember(Long userId, String key, Previous previous) {
        try {
            idempotency.remember(userId, key, previous);
        } catch (RuntimeException e) {
            log.warn("취소 응답 기억 실패(취소는 완료, 같은 키 재전송은 ALREADY_CANCELED) paymentId={}", previous.paymentId(), e);
        }
    }

    /** 잠그지 않고 읽어 먼저 거른 뒤 카드 종류별로 처리한다. 각 경로가 잠근 뒤 다시 검사한다. */
    private PaymentCancelResponse process(Long userId, Long paymentId) {
        CardType type = requireCancelable(find(userId, paymentId));
        if (type == CardType.CREDIT) {
            return transaction.execute(status -> cancelCredit(userId, paymentId));
        }
        return cancelCheck(userId, paymentId);
    }

    private PaymentCancelResponse cancelCredit(Long userId, Long paymentId) {
        PaymentTransaction payment = payments.findForCancel(paymentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireCancelable(payment);
        LocalDateTime now = nowUtc();

        payment.cancel(now);
        payments.saveAndFlush(payment);
        CreditBillingCanceler.Result billed = billing.cancel(payment, now);
        PaymentCancelResponse.Restored restored = ledger.reverse(payment, now);
        log.info("결제 취소 paymentId={} billItems={} refunds={}", paymentId, billed.billItems().size(),
                billed.refunds().size());
        return new PaymentCancelResponse(payment.getId(), payment.getStatus(), payment.getCanceledAt(), restored,
                billed.billItems(), billed.refunds(), null);
    }

    /**
     * 체크카드: 정산 잠금 안에서 환불 입금을 끝낸 뒤 CANCELED로 저장한다. 입금하지 못하면(예외) 결제는 APPROVED로 남는다.
     * 같은 결제를 다른 키로 동시에 취소하면 뒤 요청은 잠금을 못 잡아 409 처리 중, 앞 요청이 끝난 뒤에는 409 ALREADY_CANCELED다.
     */
    private PaymentCancelResponse cancelCheck(Long userId, Long paymentId) {
        RedisLocks.Lock settleLock = locks.tryLock(WithdrawalRecovery.settleLockKey(paymentId),
                properties.settleLockTtl()).orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        "결제 취소를 처리하고 있습니다. 잠시 후 결과를 확인해 주세요."));
        try (settleLock) {
            PaymentTransaction snapshot = find(userId, paymentId);
            requireCancelable(snapshot);
            CheckCardRefunder.Refunded refunded = refunder.refund(snapshot);
            return transaction.execute(status -> {
                PaymentTransaction payment = payments.findForCancel(paymentId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                requireCancelable(payment);
                LocalDateTime now = nowUtc();
                payment.cancel(now);
                payments.saveAndFlush(payment);
                PaymentCancelResponse.Restored restored = ledger.reverse(payment, now);
                log.info("체크카드 결제 취소 paymentId={} refund={}", paymentId, refunded.amount());
                return new PaymentCancelResponse(payment.getId(), payment.getStatus(), payment.getCanceledAt(),
                        restored, List.of(), List.of(), refunded.balanceAfter());
            });
        }
    }

    /** 이 사용자의 결제. 없거나 남의 결제면 404. */
    private PaymentTransaction find(Long userId, Long paymentId) {
        return payments.findById(paymentId)
                .filter(payment -> userId.equals(payment.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 취소할 수 있는 결제인지 보고 카드 종류를 돌려준다. 잠근 뒤에 다시 보므로 동시 취소의 뒤 요청은 여기서 ALREADY_CANCELED가 된다.
     * 임시(초안 Q18 제안값): SEED 거래는 막는다. 등록 해제된 카드의 결제는 허용한다(체크카드는 입금 계좌가 없으면 환불 단계에서 409).
     */
    private CardType requireCancelable(PaymentTransaction payment) {
        if (payment.getStatus() == PaymentStatus.CANCELED) {
            throw new BusinessException(ErrorCode.ALREADY_CANCELED);
        }
        if (payment.getStatus() != PaymentStatus.APPROVED) {
            throw notCancelable("NOT_APPROVED", Map.of("status", payment.getStatus().name()));
        }
        if (payment.getSource() == TransactionSource.SEED) {
            throw notCancelable("SEED_TRANSACTION", Map.of());
        }
        return cards.cardTypeOfProduct(payment.getCardProductId())
                .orElseThrow(() -> new IllegalStateException("승인된 결제의 카드 상품이 없습니다 (결제 " + payment.getId() + ")"));
    }

    /** 409 PAYMENT_NOT_CANCELABLE + details.reason. 체크카드 환불({@link CheckCardRefunder})도 쓴다. */
    static BusinessException notCancelable(String reason, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("reason", reason);
        return new BusinessException(ErrorCode.PAYMENT_NOT_CANCELABLE, details);
    }

    /** 처음 결과를 다시 보여 준다. 같은 키에 다른 내용이면 409. */
    private static PaymentCancelResponse replay(Previous previous, String fingerprint) {
        if (!previous.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return previous.response();
    }

    /** 요청 내용: 결제 번호와 사유. 사유는 Redis에 원문을 남기지 않게 해시로 넣는다. */
    static String fingerprint(Long paymentId, PaymentCancelRequest request) {
        return paymentId + ":" + sha256(request.reason() == null ? "" : request.reason());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
