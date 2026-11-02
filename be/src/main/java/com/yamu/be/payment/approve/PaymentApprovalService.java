package com.yamu.be.payment.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.IdempotencyKeys;
import com.yamu.be.payment.PaymentExpiry;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Attempt;
import com.yamu.be.payment.approve.ApprovalIdempotencyStore.Previous;
import com.yamu.be.payment.approve.PaymentApprovalResponse.Benefit;
import com.yamu.be.payment.approve.PaymentApprovalResponse.Failure;
import com.yamu.be.payment.approve.PaymentApprovalResponse.SelectedCard;
import com.yamu.be.payment.approve.PaymentApprovalResponse.SuggestedCard;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 승인 요청(API 명세 「결제 승인 요청 (카드 확정)」, 「결제 상태·예외 처리」 4-2·5장. FR-019~FR-021, FR-025, NFR-005).
 *
 * <pre>
 * ① Idempotency-Key(UUID)·본문 계약(plan·installmentMonths 금지) → 요청 지문
 * ② 멱등: 같은 키 재전송은 처음 응답, 다른 내용은 409, 처리 중이면 409
 * ③ 결제 소유 확인(남의 결제 404) → 상태·마감 선검사(만료면 EXPIRED 저장 후 410)
 * ④ 재승인 횟수(최대 3회, 임시) → 카드 결정(직접 고름: 소유 404·적격 400, 생략: 1순위 추천)
 * ⑤ tx1: REQUESTED·DECLINED → PROCESSING 선점(@Version으로 동시 요청 1건만)
 * ⑥ 승인 실행(PaymentApprover, DB 트랜잭션 밖. 체크카드 출금은 Task 5.2.3)
 * ⑦ tx2: APPROVED(원장 반영 포트) 또는 DECLINED 저장 → 바코드 USED → 응답·멱등 기억
 * </pre>
 *
 * <p>깨질 수 있는 곳: 연타·두 기기(다른 키)는 ⑤의 version 충돌로 한 건만 PROCESSING이 되고 나머지는 409 PAYMENT_IN_PROGRESS.
 * 같은 키 재전송은 ②의 Redis 응답과 잠금. 승인 실행 중 예외(금융망 무응답)는 PROCESSING에 둔 채 502이고, 복구는 Task 5.2.3.
 * 후보 없음은 거절로 저장하지 않는다(failure_code 허용값에 없고 시도 횟수만 소모). 마감은 ③과 ⑤에서 두 번 본다.
 */
@Slf4j
@Service
public class PaymentApprovalService {

    static final int SUGGESTED_CARDS = 2;

    private final PaymentTransactionRepository payments;
    private final CardQueryService cardQuery;
    private final PaymentCardLookup cards;
    private final RecommendationSnapshotStore snapshots;
    private final CardRecommender recommender;
    private final PaymentApprover approver;
    private final ApprovalLedger ledger;
    private final ApprovalIdempotencyStore idempotency;
    private final ApprovalAttemptStore attempts;
    private final PaymentExpiry expiry;
    private final BarcodeStore barcodes;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final PosProperties posProperties;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public PaymentApprovalService(PaymentTransactionRepository payments, CardQueryService cardQuery,
            PaymentCardLookup cards, RecommendationSnapshotStore snapshots, CardRecommender recommender,
            PaymentApprover approver, ApprovalLedger ledger, ApprovalIdempotencyStore idempotency,
            ApprovalAttemptStore attempts, PaymentExpiry expiry, BarcodeStore barcodes, RedisLocks locks,
            TransactionTemplate transaction, ObjectMapper objectMapper, PosProperties posProperties,
            PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.cardQuery = cardQuery;
        this.cards = cards;
        this.snapshots = snapshots;
        this.recommender = recommender;
        this.approver = approver;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.attempts = attempts;
        this.expiry = expiry;
        this.barcodes = barcodes;
        this.locks = locks;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
        this.posProperties = posProperties;
        this.properties = properties;
        this.clock = clock;
    }

    public PaymentApprovalResponse approve(AuthUser me, Long paymentId, PaymentApprovalRequest request,
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
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS));
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            Optional<Previous> done = idempotency.find(userId, key);
            if (done.isPresent()) {
                return replay(done.get(), fingerprint);
            }
            PaymentApprovalResponse response = process(userId, paymentId, request, key);
            idempotency.remember(userId, key, new Previous(fingerprint, paymentId, response));
            return response;
        }
    }

    private PaymentApprovalResponse process(Long userId, Long paymentId, PaymentApprovalRequest request, String key) {
        PaymentTransaction payment = payments.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireConfirmable(payment);
        if (attempts.count(paymentId) >= properties.maxAttempts()) {
            throw new BusinessException(ErrorCode.RETRY_LIMIT_EXCEEDED,
                    Map.of("maxAttempts", properties.maxAttempts()));
        }
        PaymentTerms terms = termsOf(payment);
        RecommendationResult recommendation = recommendationOf(payment, terms);
        Selection selection = select(userId, request, terms, recommendation);

        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        takeProcessing(paymentId, selection);
        Attempt attempt = attempts.start(paymentId, selection.card().userCardId(), key, nowUtc);
        log.info("결제 승인 시작 paymentId={} attempt={} card=****{}", paymentId, attempt.no(), selection.card().last4());

        ApprovalOutcome outcome;
        try {
            outcome = approver.approve(new ApprovalCommand(paymentId, userId, terms, selection.card(),
                    selection.candidate(), payment.getRequestedAt(), nowUtc, attempt.no()));
        } catch (RuntimeException e) {
            // 결과 불명. 돈이 빠졌을 수 있으므로 PROCESSING에 둔다. 거래내역으로 확정하는 복구는 Task 5.2.3
            log.warn("결제 승인 실행 실패, PROCESSING 유지 paymentId={} attempt={}", paymentId, attempt.no(), e);
            throw e;
        }
        PaymentTransaction settled = settle(paymentId, outcome, nowUtc);
        if (outcome instanceof ApprovalOutcome.Approved) {
            markBarcodeUsed(paymentId);
            attempts.finish(attempt, PaymentStatus.APPROVED.name(), null, paymentId);
        } else if (outcome instanceof ApprovalOutcome.Declined declined) {
            attempts.finish(attempt, PaymentStatus.DECLINED.name(), declined.code().name(), paymentId);
        }
        log.info("결제 승인 결과 paymentId={} attempt={} status={} failure={}", paymentId, attempt.no(),
                settled.getStatus(), settled.getFailureCode());
        return response(settled, selection, recommendation, outcome, attempt.no());
    }

    /** 쓰기 전에 거르는 상태 검사. 마감이 지난 REQUESTED·DECLINED는 여기서 EXPIRED로 저장한다(Q4, 임시). */
    private void requireConfirmable(PaymentTransaction payment) {
        if (!payment.isConfirmable()) {
            throw byStatus(payment.getStatus());
        }
        if (expiry.isPastDeadline(payment)) {
            throw byStatus(expire(payment.getId()));
        }
    }

    /** 승인할 수 없는 상태를 응답 코드로. 선점 경쟁에서 진 뒤 다시 읽은 REQUESTED·DECLINED도 "처리 중"이다. */
    private static BusinessException byStatus(PaymentStatus status) {
        return switch (status) {
            case EXPIRED -> new BusinessException(ErrorCode.PAYMENT_EXPIRED);
            case APPROVED, CANCELED -> new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    Map.of("status", status.name()));
            default -> new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        };
    }

    /** DB에 보존된 POS 조건. 앱 본문의 값은 쓰지 않는다(10/29 결정). */
    static PaymentTerms termsOf(PaymentTransaction payment) {
        return payment.isInstallment()
                ? PaymentTerms.installment(payment.getRequestedAmount(), payment.getInstallmentMonths())
                : PaymentTerms.lumpSum(payment.getRequestedAmount());
    }

    /** 스냅샷이 사라졌으면(보관 종료·장애) 현재 규칙으로 다시 계산해 쓴다(Q2, 임시). */
    private RecommendationResult recommendationOf(PaymentTransaction payment, PaymentTerms terms) {
        return snapshots.find(payment.getId()).orElseGet(() -> {
            MerchantContextSnapshot context = readContext(payment);
            RecommendationResult result = recommender.recommend(new RecommendationRequest(payment.getId(),
                    payment.getUserId(), terms,
                    new MerchantContext(payment.getMerchantId(), context == null ? null : context.categoryCode(),
                            payment.getPaymentChannel().name(),
                            payment.getFacilityType() == null ? null : payment.getFacilityType().name()),
                    payment.getRequestedAt()));
            snapshots.save(payment.getId(), result, posProperties.snapshotTtl());
            log.info("추천 스냅샷이 없어 다시 계산 paymentId={}", payment.getId());
            return result;
        });
    }

    /**
     * 승인할 카드. 직접 고른 카드는 소유(404) → 적격(400) 순으로 본다(명세 POL-013 공통 계약: 소유권 확인을 먼저).
     * 생략하면 저장된 조건으로 계산한 1순위 후보다.
     */
    private Selection select(Long userId, PaymentApprovalRequest request, PaymentTerms terms,
            RecommendationResult recommendation) {
        if (request.userSelected()) {
            Long cardId = request.userCardId();
            cardQuery.requireRegisteredCard(userId, cardId);
            ActiveCard card = activeCard(userId, cardId)
                    .orElseThrow(() -> notEligible("CARD_INACTIVE", "추천에서 제외한 카드예요. 카드 설정에서 다시 켜 주세요."));
            requireEligible(card, terms, recommendation);
            Optional<Candidate> candidate = recommendation.candidate(cardId);
            CardSummary summary = candidate.map(Candidate::card).orElseGet(() -> summary(card));
            return new Selection(summary, candidate.orElse(null), candidate.map(Candidate::rank).orElse(null), true);
        }
        if (!recommendation.isAvailable()) {
            if (recommendation.unavailableReason() == UnavailableReason.DATA_INSUFFICIENT) {
                throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "추천을 확정할 수 없어요. 결제할 카드를 직접 골라 주세요.",
                        Map.of("reason", UnavailableReason.DATA_INSUFFICIENT.name()));
            }
            throw notEligible(UnavailableReason.NO_ELIGIBLE_CARD.name(), "결제할 수 있는 카드가 없어요.");
        }
        Candidate top = recommendation.topCandidate().orElseThrow();
        ActiveCard card = activeCard(userId, top.card().userCardId())
                .orElseThrow(() -> notEligible("CARD_INACTIVE", "1순위 카드를 지금은 쓸 수 없어요. 결제할 카드를 직접 골라 주세요."));
        requireEligible(card, terms, recommendation);
        return new Selection(top.card(), top, top.rank(), false);
    }

    private Optional<ActiveCard> activeCard(Long userId, Long cardId) {
        return cards.findActiveCards(userId).stream().filter(c -> c.userCardId().equals(cardId)).findFirst();
    }

    /** 카드 상태를 지금 다시 본다(추천 뒤 바뀌었을 수 있음). 체크카드 할부는 명세대로 INVALID_INSTALLMENT다. */
    private static void requireEligible(ActiveCard card, PaymentTerms terms, RecommendationResult recommendation) {
        if (card.issuerStatus() == CardIssuerStatus.SUSPENDED) {
            throw notEligible("CARD_SUSPENDED", "카드사에서 정지한 카드예요.");
        }
        if (card.issuerStatus() == CardIssuerStatus.EXPIRED) {
            throw notEligible("CARD_EXPIRED", "유효기간이 지난 카드예요.");
        }
        if (!terms.allows(card.cardType())) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "userCardId", "체크카드는 할부로 결제할 수 없습니다.");
        }
        recommendation.excluded(card.userCardId()).ifPresent(excluded -> {
            throw notEligible(excluded.reason().name(), excluded.message());
        });
    }

    private static BusinessException notEligible(String reason, String message) {
        return new BusinessException(ErrorCode.CARD_NOT_ELIGIBLE, message, Map.of("reason", reason));
    }

    /**
     * tx1: REQUESTED·DECLINED → PROCESSING. saveAndFlush에서 version이 맞지 않으면 다른 요청이 먼저 선점한 것이다.
     * 마감은 선점 직전의 서버 시각으로 다시 본다.
     */
    private void takeProcessing(Long paymentId, Selection selection) {
        try {
            transaction.execute(status -> {
                PaymentTransaction payment = payments.findById(paymentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                if (!payment.isConfirmable()) {
                    throw byStatus(payment.getStatus());
                }
                if (expiry.isPastDeadline(payment)) {
                    throw new DeadlinePassed();
                }
                payment.startApproval(selection.card().userCardId(), selection.card().cardProductId());
                payments.saveAndFlush(payment);
                return payment;
            });
        } catch (DeadlinePassed e) {
            throw byStatus(expire(paymentId));
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            PaymentStatus current = payments.findById(paymentId).map(PaymentTransaction::getStatus)
                    .orElse(PaymentStatus.PROCESSING);
            log.info("결제 승인 선점 경쟁에서 밀림 paymentId={} status={}", paymentId, current);
            throw byStatus(current);
        }
    }

    /** tx2: 승인 결과 저장. 승인이면 원장 반영 포트까지 같은 트랜잭션이다. */
    private PaymentTransaction settle(Long paymentId, ApprovalOutcome outcome, LocalDateTime now) {
        return transaction.execute(status -> {
            PaymentTransaction payment = payments.findById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (outcome instanceof ApprovalOutcome.Approved approved) {
                payment.approve(approved.approvedAmount(), approved.recognizedPerformanceAmount(),
                        approved.performanceMonth(), now, approved.finTransactionNo());
                payments.saveAndFlush(payment);
                ledger.recordApproval(payment, approved);
            } else if (outcome instanceof ApprovalOutcome.Declined declined) {
                payment.decline(declined.code().name());
                payments.saveAndFlush(payment);
            }
            return payment;
        });
    }

    /** 마감이 지난 결제를 EXPIRED로 저장하고 바코드도 EXPIRED로 둔다. 바코드 반영 실패는 로그만 남긴다. */
    private PaymentStatus expire(Long paymentId) {
        PaymentStatus after = expiry.expire(paymentId);
        if (after == PaymentStatus.EXPIRED) {
            try {
                barcodes.expireByPayment(paymentId);
            } catch (RuntimeException e) {
                log.warn("결제 만료 뒤 바코드 만료 반영 실패 paymentId={}", paymentId, e);
            }
        }
        return after;
    }

    /** 승인 뒤 바코드를 USED로. 바코드가 보관 종료로 없어도 승인은 유효하다. */
    private void markBarcodeUsed(Long paymentId) {
        try {
            if (!barcodes.markUsed(paymentId)) {
                log.warn("승인 뒤 바코드를 USED로 바꾸지 못함(보관 종료 또는 다른 상태) paymentId={}", paymentId);
            }
        } catch (RuntimeException e) {
            log.warn("승인 뒤 바코드 USED 반영 실패 paymentId={}", paymentId, e);
        }
    }

    private PaymentApprovalResponse response(PaymentTransaction payment, Selection selection,
            RecommendationResult recommendation, ApprovalOutcome outcome, int attemptNo) {
        CardSummary card = selection.card();
        SelectedCard selected = new SelectedCard(card.userCardId(), nickname(card), masked(card.last4()),
                card.cardType(), selection.recommendedRank(), selection.userSelected());
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;

        if (outcome instanceof ApprovalOutcome.Approved approved) {
            List<Benefit> benefits = approved.benefits().stream()
                    .map(b -> new Benefit(null, b.benefitId(), b.name(), b.type(), b.amount(), b.realization(),
                            false))
                    .toList();
            return new PaymentApprovalResponse(payment.getId(), payment.getStatus(),
                    ApprovalNumbers.of(payment.getApprovedAt(), payment.getId()), payment.getApprovedAt(), selected,
                    payment.getRequestedAmount(), plan, months, benefits, approved.chargedAmount(), null, List.of(),
                    null, List.of(), null, null, null, List.of());
        }
        ApprovalOutcome.Declined declined = (ApprovalOutcome.Declined) outcome;
        int remaining = Math.max(0, properties.maxAttempts() - attemptNo);
        boolean retryable = remaining > 0 && !expiry.isPastDeadline(payment);
        List<SuggestedCard> suggested = retryable
                ? suggestions(recommendation, attempts.triedCardIds(payment.getId()))
                : List.of();
        return new PaymentApprovalResponse(payment.getId(), payment.getStatus(), null, null, selected,
                payment.getRequestedAmount(), plan, months, List.of(), null, null, List.of(), null, List.of(),
                new Failure(declined.code().name(), declined.message(), declined.details()), retryable, remaining,
                suggested);
    }

    /** 거절 응답의 "B카드로 다시 결제" 후보: 추천 순서대로, 이미 시도한 카드는 빼고 최대 2장(프로토타입 P-06b). */
    private static List<SuggestedCard> suggestions(RecommendationResult recommendation, Set<Long> tried) {
        return recommendation.candidates().stream()
                .filter(c -> !tried.contains(c.card().userCardId()))
                .limit(SUGGESTED_CARDS)
                .map(c -> new SuggestedCard(c.card().userCardId(), nickname(c.card()), masked(c.card().last4()),
                        c.card().cardType(), c.rank(), c.netBenefit()))
                .toList();
    }

    /** 처음 결과를 다시 보여 준다. 같은 키에 다른 내용이면 409. */
    private static PaymentApprovalResponse replay(Previous previous, String fingerprint) {
        if (!previous.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return previous.response();
    }

    /** 요청 내용: 결제 번호와 고른 카드(생략은 auto). 같은 키로 다른 결제·다른 카드를 보내면 409. */
    static String fingerprint(Long paymentId, PaymentApprovalRequest request) {
        return paymentId + ":" + (request.userSelected() ? request.userCardId().toString() : "auto");
    }

    private static CardSummary summary(ActiveCard card) {
        return new CardSummary(card.userCardId(), card.cardProductId(), card.cardType(), card.defaultCard(),
                card.alias(), card.last4(), card.issuerCode(), card.productName());
    }

    private static String nickname(CardSummary card) {
        return card.alias() != null ? card.alias() : card.productName();
    }

    private static String masked(String last4) {
        return "****-****-****-" + last4;
    }

    private MerchantContextSnapshot readContext(PaymentTransaction payment) {
        if (payment.getMerchantContextSnapshot() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(payment.getMerchantContextSnapshot(), MerchantContextSnapshot.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 승인할 카드와 추천 근거.
     *
     * @param candidate       추천 후보였으면 그 후보. 추천 밖의 카드를 직접 골랐으면 null
     * @param recommendedRank 추천 순위. 후보가 아니면 null
     */
    private record Selection(CardSummary card, Candidate candidate, Integer recommendedRank, boolean userSelected) {}

    /** 선점 트랜잭션 안에서 마감을 발견했을 때 롤백하고 밖에서 만료 처리하기 위한 신호. */
    private static final class DeadlinePassed extends RuntimeException {
        DeadlinePassed() {
            super("결제 확정 마감이 지났습니다.", null, false, false);
        }
    }
}
