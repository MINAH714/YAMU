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
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CreditBillingWriter;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.pos.MerchantContextSnapshot;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
 * ③ 결제 소유 확인(남의 결제 404) → PROCESSING이면 결과 불명 복구(WithdrawalRecovery) → 상태·마감 선검사(만료면 EXPIRED 저장 후 410)
 * ④ 재승인 횟수(최대 3회, 임시) → 카드 결정(직접 고름: 소유 404·적격 400, 생략: 1순위 추천) → 사전 검사(신용카드 결제일·한도 자료 없음 422)
 * ⑤ 결제별 정산 잠금 → tx1: REQUESTED·DECLINED → PROCESSING 선점(@Version으로 동시 요청 1건만)
 * ⑥ 승인 실행(PaymentApprover, DB 트랜잭션 밖. 체크카드는 금융망 출금, 신용카드는 한도 확인·청구 계획)
 * ⑦ 신용카드면 회차가 걸리는 달의 청구서 확보(별도 트랜잭션) → tx2: APPROVED(원장 반영 포트·청구 회차 저장) 또는 DECLINED 저장
 *    → 바코드 USED → 응답·멱등 기억
 * </pre>
 *
 * <p>깨질 수 있는 곳: 연타·두 기기(다른 키)는 ⑤의 version 충돌로 한 건만 PROCESSING이 되고 나머지는 409 PAYMENT_IN_PROGRESS.
 * 같은 키 재전송은 ②의 Redis 응답과 잠금. 승인 실행 중 결과 불명(금융망 무응답)은 PROCESSING에 둔 채 502이고,
 * 다음 요청·주기 작업이 거래내역으로 확정한다(③). 금융망에 보내지도 못했으면 REQUESTED로 되돌린다.
 * 늦게 온 옛 시도의 결과는 tx2에서 요청 번호를 대조해 막는다. 후보 없음은 거절로 저장하지 않는다. 마감은 ③과 ⑤에서 두 번 본다.
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
    private final CreditBillingWriter billing;
    private final ApprovalIdempotencyStore idempotency;
    private final ApprovalAttemptStore attempts;
    private final PaymentExpiry expiry;
    private final WithdrawalRecovery recovery;
    private final BarcodeStore barcodes;
    private final RedisLocks locks;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final PosProperties posProperties;
    private final PaymentApprovalProperties properties;
    private final Clock clock;

    public PaymentApprovalService(PaymentTransactionRepository payments, CardQueryService cardQuery,
            PaymentCardLookup cards, RecommendationSnapshotStore snapshots, CardRecommender recommender,
            PaymentApprover approver, ApprovalLedger ledger, CreditBillingWriter billing,
            ApprovalIdempotencyStore idempotency,
            ApprovalAttemptStore attempts, PaymentExpiry expiry, WithdrawalRecovery recovery, BarcodeStore barcodes,
            RedisLocks locks, TransactionTemplate transaction, ObjectMapper objectMapper,
            PosProperties posProperties, PaymentApprovalProperties properties, Clock clock) {
        this.payments = payments;
        this.cardQuery = cardQuery;
        this.cards = cards;
        this.snapshots = snapshots;
        this.recommender = recommender;
        this.approver = approver;
        this.ledger = ledger;
        this.billing = billing;
        this.idempotency = idempotency;
        this.attempts = attempts;
        this.expiry = expiry;
        this.recovery = recovery;
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
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            // 앞 요청이 결과를 모른 채 끝났을 수 있다. 거래내역으로 확정한 뒤 그 결과대로 답한다(Task 5.2.3)
            PaymentStatus recovered = recovery.recover(paymentId);
            if (recovered == PaymentStatus.APPROVED) {
                return recoveredResponse(userId, paymentId, request);
            }
            if (recovered != PaymentStatus.REQUESTED && recovered != PaymentStatus.DECLINED) {
                throw byStatus(recovered == null ? PaymentStatus.PROCESSING : recovered);
            }
            payment = payments.findByIdAndUserId(paymentId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        }
        requireConfirmable(payment);
        if (attempts.count(paymentId) >= properties.maxAttempts()) {
            throw new BusinessException(ErrorCode.RETRY_LIMIT_EXCEEDED,
                    Map.of("maxAttempts", properties.maxAttempts()));
        }
        PaymentTerms terms = termsOf(payment);
        RecommendationResult recommendation = recommendationOf(payment, terms);
        Selection selection = select(userId, request, terms, recommendation);
        // 자료만으로 승인할 수 없는 카드는 선점·시도 횟수를 쓰기 전에 거른다(신용카드 결제일·한도 자료 없음 → 422)
        approver.precheck(userId, selection.card(), terms);

        // 출금 실행·결과 저장과 복구가 같은 결제에 겹치지 않게 결제별로 잠근다
        Optional<RedisLocks.Lock> settle = locks.tryLock(WithdrawalRecovery.settleLockKey(paymentId),
                properties.settleLockTtl());
        if (settle.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        try (RedisLocks.Lock ignored = settle.get()) {
            return execute(userId, paymentId, key, payment, terms, recommendation, selection);
        }
    }

    private PaymentApprovalResponse execute(Long userId, Long paymentId, String key, PaymentTransaction payment,
            PaymentTerms terms, RecommendationResult recommendation, Selection selection) {
        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        takeProcessing(paymentId, selection);
        Attempt attempt = attempts.start(paymentId, selection.card().userCardId(), key, nowUtc);
        log.info("결제 승인 시작 paymentId={} attempt={} card=****{}", paymentId, attempt.no(), selection.card().last4());

        ApprovalOutcome outcome;
        try {
            outcome = approver.approve(new ApprovalCommand(paymentId, userId, terms, selection.card(),
                    selection.candidate(), payment.getRequestedAt(), nowUtc, attempt.no()));
        } catch (ApprovalNotSentException e) {
            // 금융망에 간 적이 없다. 결제를 승인 전으로 되돌려 다시 시도할 수 있게 한다(시도 횟수에 세지 않음)
            release(paymentId);
            attempts.finish(attempt, ApprovalAttemptStore.NOT_SENT, null, paymentId);
            log.warn("결제 승인 실행을 보내지 못해 되돌림 paymentId={} attempt={}: {}", paymentId, attempt.no(), e.getMessage());
            throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
        } catch (RuntimeException e) {
            // 결과 불명. 돈이 빠졌을 수 있으므로 PROCESSING에 둔다. 다음 요청·주기 작업이 거래내역으로 확정한다
            log.warn("결제 승인 실행 실패, PROCESSING 유지 paymentId={} attempt={}", paymentId, attempt.no(), e);
            throw e;
        }
        Map<YearMonth, CardBill> bills = Map.of();
        if (outcome instanceof ApprovalOutcome.Approved approved && approved.hasInstallments()) {
            // 신용카드: 회차가 걸리는 달의 청구서를 먼저 확보한다(별도 트랜잭션, 같은 달은 재사용).
            // 여기서 실패하면 결제는 PROCESSING에 남고, 요청 번호가 없으므로 복구가 유예 뒤 REQUESTED로 되돌린다
            bills = billing.ensureBills(userId, selection.card().userCardId(), approved.installments());
        }
        Settled settled = settle(paymentId, outcome, nowUtc, bills);
        if (outcome instanceof ApprovalOutcome.Approved approved) {
            markBarcodeUsed(paymentId);
            attempts.finish(attempt, ApprovalAttemptStore.APPROVED, null, paymentId);
            log.info("결제 승인 결과 paymentId={} attempt={} status=APPROVED billItems={}", paymentId, attempt.no(),
                    settled.items().size());
            return approvedResponse(settled.payment(), selection, approved.chargedAmount(), approved.benefits(),
                    settled.items(), settled.bills());
        }
        ApprovalOutcome.Declined declined = (ApprovalOutcome.Declined) outcome;
        attempts.finish(attempt, ApprovalAttemptStore.DECLINED, declined.code().name(), paymentId);
        log.info("결제 승인 결과 paymentId={} attempt={} status=DECLINED failure={}", paymentId, attempt.no(),
                declined.code());
        return declinedResponse(settled.payment(), selection, recommendation, declined, attempt.no());
    }

    /** 쓰기 전에 거르는 상태 검사. 마감이 지난 REQUESTED·DECLINED는 여기서 EXPIRED로 저장한다(Q4, 임시). */
    private void requireConfirmable(PaymentTransaction payment) {
        if (!payment.isConfirmable()) {
            throw byStatus(payment.getStatus());
        }
        if (expiry.isPastDeadline(payment)) {
            throw byStatus(expiry.expire(payment.getId()));
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
            throw byStatus(expiry.expire(paymentId));
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            PaymentStatus current = payments.findById(paymentId).map(PaymentTransaction::getStatus)
                    .orElse(PaymentStatus.PROCESSING);
            log.info("결제 승인 선점 경쟁에서 밀림 paymentId={} status={}", paymentId, current);
            throw byStatus(current);
        }
    }

    /**
     * tx2: 승인 결과 저장. 승인이면 원장 반영 포트와 신용카드 청구 회차 저장까지 같은 트랜잭션이다
     * (회차의 FK가 결제 행의 카드와 맞아야 하므로 승인 저장 뒤에 넣는다).
     * 출금에 쓴 요청 번호가 행의 번호와 다르면 옛 시도의 결과가 늦게 온 것이므로 저장하지 않는다(운영 확인).
     */
    private Settled settle(Long paymentId, ApprovalOutcome outcome, LocalDateTime now,
            Map<YearMonth, CardBill> bills) {
        return transaction.execute(status -> {
            PaymentTransaction payment = payments.findById(paymentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (outcome instanceof ApprovalOutcome.Approved approved) {
                if (approved.finRequestNo() != null && !approved.finRequestNo().equals(payment.getFinRequestNo())) {
                    log.error("승인 결과의 출금 요청 번호가 결제 행과 다름, 저장하지 않음 paymentId={} outcome={} row={}",
                            paymentId, approved.finRequestNo(), payment.getFinRequestNo());
                    throw new IllegalStateException("출금 요청 번호가 결제 행과 다릅니다 (결제 " + paymentId + ")");
                }
                payment.approve(approved.approvedAmount(), approved.recognizedPerformanceAmount(),
                        approved.performanceMonth(), now, approved.finTransactionNo());
                payments.saveAndFlush(payment);
                ledger.recordApproval(payment, approved);
                List<BillItem> items = approved.hasInstallments()
                        ? billing.writeItems(payment, approved.installments(), bills)
                        : List.of();
                return new Settled(payment, items, bills);
            }
            ApprovalOutcome.Declined declined = (ApprovalOutcome.Declined) outcome;
            payment.decline(declined.code().name());
            payments.saveAndFlush(payment);
            return new Settled(payment, List.of(), Map.of());
        });
    }

    /** 금융망에 보내지 못한 시도를 없던 일로. PROCESSING이 아니면(다른 처리가 바꿈) 그대로 둔다. */
    private void release(Long paymentId) {
        try {
            transaction.execute(status -> {
                PaymentTransaction payment = payments.findById(paymentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                if (payment.getStatus() == PaymentStatus.PROCESSING) {
                    payment.releaseApproval();
                    payments.saveAndFlush(payment);
                }
                return null;
            });
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            log.warn("승인 되돌리기 중 결제 행이 바뀜 paymentId={}", paymentId);
        }
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

    /**
     * 복구가 승인으로 확정한 결제의 응답을 행에서 만든다. 승인된 카드는 행의 카드이고, 요청이 다른 카드를 지정했어도 그 결과를 돌려준다
     * (멱등 지문은 요청 기준 그대로). 앱은 selectedCard를 응답에서 읽어야 한다.
     */
    private PaymentApprovalResponse recoveredResponse(Long userId, Long paymentId, PaymentApprovalRequest request) {
        PaymentTransaction payment = payments.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Long cardId = payment.getUserCardId();
        CardSummary card = activeCard(userId, cardId).map(PaymentApprovalService::summary)
                .or(() -> cardQuery.findRegisteredCard(userId, cardId)
                        .map(ref -> new CardSummary(ref.userCardId(), ref.cardProductId(), ref.cardType(), false,
                                null, ref.cardLast4(), null, null)))
                .orElseThrow(() -> new IllegalStateException("승인된 결제의 카드를 찾을 수 없습니다 (결제 " + paymentId + ")"));
        Integer rank = snapshots.find(paymentId).flatMap(r -> r.candidate(cardId)).map(Candidate::rank).orElse(null);
        boolean userSelected = request.userSelected() && request.userCardId().equals(cardId);
        log.info("결제 승인 요청이 복구된 승인 결과를 돌려줌 paymentId={}", paymentId);
        return approvedResponse(payment, new Selection(card, null, rank, userSelected),
                payment.getApprovedAmount(), List.of(), List.of(), Map.of());
    }

    private PaymentApprovalResponse approvedResponse(PaymentTransaction payment, Selection selection,
            Long chargedAmount, List<RecommendationResult.BenefitLine> benefitLines, List<BillItem> items,
            Map<YearMonth, CardBill> bills) {
        List<Benefit> benefits = benefitLines.stream()
                .map(b -> new Benefit(null, b.benefitId(), b.name(), b.type(), b.amount(), b.realization(), false))
                .toList();
        return new PaymentApprovalResponse(payment.getId(), payment.getStatus(),
                ApprovalNumbers.of(payment.getApprovedAt(), payment.getId()), payment.getApprovedAt(),
                selected(selection), payment.getRequestedAmount(), planOf(payment), monthsOf(payment), benefits,
                chargedAmount, null, billItems(items, bills), null, List.of(), null, null, null, List.of());
    }

    /** 신용카드 청구 회차(회차 순). 출금 예정일은 그 회차가 배정된 청구서의 값이다. 체크카드는 빈 목록. */
    private static List<PaymentApprovalResponse.BillItem> billItems(List<BillItem> items,
            Map<YearMonth, CardBill> bills) {
        return items.stream().map(item -> {
            YearMonth month = YearMonth.from(item.getBillingMonth());
            CardBill bill = bills.get(month);
            return new PaymentApprovalResponse.BillItem(item.getId(), item.getInstallmentNo(), item.getBilledAmount(),
                    month.toString(), bill == null ? null : bill.getWithdrawalDate(), item.getStatus().name());
        }).toList();
    }

    private PaymentApprovalResponse declinedResponse(PaymentTransaction payment, Selection selection,
            RecommendationResult recommendation, ApprovalOutcome.Declined declined, int attemptNo) {
        int remaining = Math.max(0, properties.maxAttempts() - attemptNo);
        boolean retryable = remaining > 0 && !expiry.isPastDeadline(payment);
        List<SuggestedCard> suggested = retryable
                ? suggestions(recommendation, attempts.triedCardIds(payment.getId()))
                : List.of();
        return new PaymentApprovalResponse(payment.getId(), payment.getStatus(), null, null, selected(selection),
                payment.getRequestedAmount(), planOf(payment), monthsOf(payment), List.of(), null, null, List.of(),
                null, List.of(), new Failure(declined.code().name(), declined.message(), declined.details()),
                retryable, remaining, suggested);
    }

    private static SelectedCard selected(Selection selection) {
        CardSummary card = selection.card();
        return new SelectedCard(card.userCardId(), nickname(card), masked(card.last4()), card.cardType(),
                selection.recommendedRank(), selection.userSelected());
    }

    private static PaymentPlan planOf(PaymentTransaction payment) {
        return payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
    }

    private static Integer monthsOf(PaymentTransaction payment) {
        return payment.isInstallment() ? payment.getInstallmentMonths() : null;
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

    /** tx2가 저장한 것: 결제 행, 신용카드면 청구 회차와 그 회차가 배정된 청구서(청구 월별). */
    private record Settled(PaymentTransaction payment, List<BillItem> items, Map<YearMonth, CardBill> bills) {}

    /** 선점 트랜잭션 안에서 마감을 발견했을 때 롤백하고 밖에서 만료 처리하기 위한 신호. */
    private static final class DeadlinePassed extends RuntimeException {
        DeadlinePassed() {
            super("결제 확정 마감이 지났습니다.", null, false, false);
        }
    }
}
