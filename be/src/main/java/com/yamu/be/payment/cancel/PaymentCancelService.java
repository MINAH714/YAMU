package com.yamu.be.payment.cancel;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.IdempotencyKeys;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
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
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 전체 취소(API 명세 「결제 취소」, POL-006, FR-068). 이번 작업은 신용카드만 처리한다(체크카드 입금은 Task 5.3.3).
 *
 * <pre>
 * ① Idempotency-Key(UUID)·본문 계약(취소 금액 금지) → 요청 지문
 * ② 멱등: 같은 키 재전송은 처음 응답, 다른 내용은 409, 처리 중이면 409
 * ③ 한 트랜잭션: 결제 행 잠금(남의 결제 404) → 상태·출처·카드 종류 검사 → CANCELED
 *    → 신용카드 회차 처리(CreditBillingCanceler) → 실적·한도 복구(CancellationLedger)
 * </pre>
 *
 * <p>깨질 수 있는 곳: 연타·두 기기(다른 키)는 ③의 행 잠금으로 줄을 서고, 뒤 요청은 CANCELED를 읽어 409 ALREADY_CANCELED다.
 * 그래서 회차 처리·환급 행·실적 복구가 한 번만 일어난다(환급은 {@code uk_refund_item}도 막는다). 같은 키 재전송은 ②의 Redis 응답과 잠금.
 * 회차 처리나 복구가 실패하면 결제도 APPROVED로 남는다(부분 반영 없음).
 */
@Slf4j
@Service
public class PaymentCancelService {

    private final PaymentTransactionRepository payments;
    private final PaymentCardLookup cards;
    private final CreditBillingCanceler billing;
    private final CancellationLedger ledger;
    private final CancelIdempotencyStore idempotency;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public PaymentCancelService(PaymentTransactionRepository payments, PaymentCardLookup cards,
            CreditBillingCanceler billing, CancellationLedger ledger, CancelIdempotencyStore idempotency,
            RedisLocks locks, TransactionTemplate transaction, PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.cards = cards;
        this.billing = billing;
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
            PaymentCancelResponse response = transaction.execute(status -> process(userId, paymentId));
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

    private PaymentCancelResponse process(Long userId, Long paymentId) {
        PaymentTransaction payment = payments.findForCancel(paymentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireCancelable(payment);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

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
     * 취소할 수 있는 결제인지. 행을 잠근 뒤에 보므로 동시 취소의 뒤 요청은 여기서 ALREADY_CANCELED가 된다.
     * 임시(초안 Q18 제안값): SEED 거래는 막는다. 체크카드는 Task 5.3.3 전까지 막는다. 등록 해제된 카드의 결제는 허용한다.
     */
    private void requireCancelable(PaymentTransaction payment) {
        if (payment.getStatus() == PaymentStatus.CANCELED) {
            throw new BusinessException(ErrorCode.ALREADY_CANCELED);
        }
        if (payment.getStatus() != PaymentStatus.APPROVED) {
            throw notCancelable("NOT_APPROVED", Map.of("status", payment.getStatus().name()));
        }
        if (payment.getSource() == TransactionSource.SEED) {
            throw notCancelable("SEED_TRANSACTION", Map.of());
        }
        CardType type = cards.cardTypeOfProduct(payment.getCardProductId())
                .orElseThrow(() -> new IllegalStateException("승인된 결제의 카드 상품이 없습니다 (결제 " + payment.getId() + ")"));
        if (type != CardType.CREDIT) {
            throw notCancelable("CHECK_CARD_NOT_SUPPORTED", Map.of());
        }
    }

    private static BusinessException notCancelable(String reason, Map<String, Object> extra) {
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
