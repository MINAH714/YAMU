package com.yamu.be.payment.approve;

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
import com.yamu.be.payment.approve.ApprovalAttemptStore.Reservation;
import com.yamu.be.payment.approve.ApprovalIdempotencyStore.Previous;
import com.yamu.be.payment.approve.PaymentApprovalResponse.Benefit;
import com.yamu.be.payment.approve.PaymentApprovalResponse.Failure;
import com.yamu.be.payment.approve.PaymentApprovalResponse.SelectedCard;
import com.yamu.be.payment.approve.PaymentApprovalResponse.SuggestedCard;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CreditBillingWriter;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.performance.PaymentPerformanceTotals;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import com.yamu.be.payment.recommend.RecommendationSnapshotStore;
import com.yamu.be.payment.request.MerchantContextSnapshot;
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
 * ② 멱등: 같은 키 재전송은 처음 응답, 다른 내용은 409, 처리 중이면 409.
 *    기억된 응답이 없어도 이 키가 만든 시도 기록이 있으면 그 응답(DB 저장이 끝났을 때) 또는 409
 * ③ 결제 소유 확인(남의 결제 404) → PROCESSING이면 결과 불명 복구(WithdrawalRecovery). 복구를 거친 요청은 새로 출금하지 않고
 *    복구 결과로 답한다(금융망에 간 적 없어 되돌린 REQUESTED만 이어서 승인) → 상태·마감 선검사(만료면 EXPIRED 저장 후 410)
 * ④ 카드 결정(직접 고름: 소유 404·이 결제에서 거절된 카드 400·적격 400, 생략: 거절된 카드를 뺀 1순위 추천)
 *    → 사전 검사(신용카드 결제일·한도 자료 없음 422)
 * ⑤ 결제별 정산 잠금(출금 실행·결과 저장·복구가 겹치지 않게) 안에서: 출금 미확인으로 거절된 결제면 앞 출금을 거래내역에서 다시 보고
 *    (찾으면 승인으로 바로잡고 409, 조회 실패면 502). 추천 후보 카드면 카드별 원장 잠금을 잡고 혜택을 다시 계산한다(Task 5.2.5,
 *    다른 결제가 그 카드를 처리 중이면 409, 재계산 실패면 409). 시작 잠금 안에서: 시도 예약(최대 3회, 임시. 횟수 검사와 붙이기가 원자적,
 *    다시 계산한 후보를 함께 남김) → tx1: REQUESTED·DECLINED → PROCESSING 선점. 선점하지 못하면 예약을 지운다
 * ⑥ 승인 실행(PaymentApprover, DB 트랜잭션 밖. 체크카드는 금융망 출금, 신용카드는 한도 확인·청구 계획). 결과 불명은 PROCESSING에 둔 채 502,
 *    금융망에 보내지도 못했으면 REQUESTED로 되돌리고 시도를 지운다(신용카드 실행 실패도 외부로 보낸 것이 없으므로 같고, 응답은 그 오류).
 *    신용카드 승인이면 이어서 회차가 걸리는 달의 청구서를 확보한다(별도 트랜잭션). 실패해도 승인·회차는 저장 전이므로 같은 방식으로 되돌리고,
 *    청구서가 이미 마감됐으면 422(BILL_CLOSED), 그 밖은 500
 * ⑦ 실행 결과(거래 번호·출금액·응답)를 로그와 시도 기록에 먼저 남긴다
 * ⑧ tx2: APPROVED(원장 반영 포트·청구 회차 저장) 또는 DECLINED 저장
 *    → 바코드 USED → 청구 회차가 생겼으면 회차를 채운 응답으로 시도 기록을 다시 적는다 → 응답 기억(실패해도 ⑦이 있으므로 응답은 그대로)
 * </pre>
 *
 * <p>깨질 수 있는 곳
 * <ul>
 *   <li>연타·두 기기(다른 키): ⑤의 시작 잠금에서 한 건만 들어가고 나머지는 409 IDEMPOTENCY_IN_PROGRESS. 잠금이 시간을 넘겨 풀려도
 *       예약의 길이 비교와 tx1의 version 충돌로 한 건만 PROCESSING이 된다.</li>
 *   <li>Redis 장애: 예약은 tx1 앞이라 예약이 실패하면 결제 행은 그대로다. tx1 뒤의 Redis 쓰기(⑦·⑧)는 실패해도 로그만 남긴다.</li>
 *   <li>tx2 실패(원장 반영 예외 등): 승인까지 롤백되어 PROCESSING에 남고 500이다. ⑦의 기록이 복구(Task 5.2.3) 근거다.</li>
 *   <li>후보 없음은 거절로 저장하지 않는다(failure_code 허용값에 없음). 예약 전에 끝나므로 시도 횟수도 쓰지 않는다.</li>
 *   <li>마감은 ③과 ⑤의 tx1에서 두 번 본다.</li>
 *   <li>같은 카드의 혜택 한도를 두 결제가 동시에 쓰기: 카드별 원장 잠금이 재계산부터 tx2 커밋까지 한 결제씩 지나가게 한다. 뒤 결제는
 *       앞 결제의 원장이 커밋된 사용량으로 다시 계산한다. 월 합계는 원장이 원자적으로 더한다. 결과 불명 복구는 이 잠금을 잡지 않고
 *       시도 기록의 값으로 적으므로(임시) 그 사이 다른 결제가 같은 한도를 쓰면 월 합계가 한도를 넘을 수 있다(한계).</li>
 * </ul>
 */
@Slf4j
@Service
public class PaymentApprovalService {

    static final int SUGGESTED_CARDS = 2;
    static final String ALREADY_DECLINED = "ALREADY_DECLINED";
    static final String CARD_LEDGER_LOCK_PREFIX = "payment:approve:card-ledger:";

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
    private final PaymentPerformanceTotals performanceTotals;

    public PaymentApprovalService(PaymentTransactionRepository payments, CardQueryService cardQuery,
            PaymentCardLookup cards, RecommendationSnapshotStore snapshots, CardRecommender recommender,
            PaymentApprover approver, ApprovalLedger ledger, CreditBillingWriter billing,
            ApprovalIdempotencyStore idempotency,
            ApprovalAttemptStore attempts, PaymentExpiry expiry, WithdrawalRecovery recovery, BarcodeStore barcodes,
            RedisLocks locks, TransactionTemplate transaction, ObjectMapper objectMapper,
            PosProperties posProperties, PaymentApprovalProperties properties, Clock clock,
            PaymentPerformanceTotals performanceTotals) {
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
        this.performanceTotals = performanceTotals;
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
                    .orElseThrow(PaymentApprovalService::inProgress);
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            Optional<Previous> done = idempotency.find(userId, key);
            if (done.isPresent()) {
                return replay(done.get(), fingerprint);
            }
            PaymentTransaction payment = payments.findByIdAndUserId(paymentId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            boolean wasUnknown = payment.getStatus() == PaymentStatus.PROCESSING;
            if (wasUnknown) {
                // 앞 요청이 결과를 모른 채 끝났을 수 있다. 거래내역으로 먼저 확정한다(Task 5.2.3)
                recovery.recover(paymentId);
                payment = payments.findByIdAndUserId(paymentId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            }
            // 기억된 응답이 없다(Redis 쓰기 실패·잠금이 실행보다 먼저 풀림·결과 불명). 이 키가 만든 시도가 있으면 다시 실행하지 않는다.
            // 결과 불명이던 시도는 누가 복구했든(이 요청·주기 작업) 결제 행으로 같은 응답을 만든다
            Optional<Attempt> mine = attempts.findByKey(paymentId, key);
            if (mine.isPresent()) {
                PaymentApprovalResponse response = replayAttempt(mine.get(), fingerprint, payment);
                remember(userId, key, new Previous(fingerprint, paymentId, response));
                return response;
            }
            if (wasUnknown) {
                Optional<PaymentApprovalResponse> recovered = afterRecovery(payment);
                if (recovered.isPresent()) {
                    remember(userId, key, new Previous(fingerprint, paymentId, recovered.get()));
                    return recovered.get();
                }
            }
            PaymentApprovalResponse response = process(payment, request, key, fingerprint);
            remember(userId, key, new Previous(fingerprint, paymentId, response));
            return response;
        }
    }

    private PaymentApprovalResponse process(PaymentTransaction payment, PaymentApprovalRequest request, String key,
            String fingerprint) {
        Long paymentId = payment.getId();
        Long userId = payment.getUserId();
        requireConfirmable(payment);
        // 빠른 거절용. 원자적인 검사는 ⑤의 예약이 한다
        if (attempts.count(paymentId) >= properties.maxAttempts()) {
            throw retryLimitExceeded();
        }
        PaymentTerms terms = termsOf(payment);
        RecommendationResult recommendation = recommendationOf(payment, terms);
        Selection selection = select(userId, request, terms, recommendation, attempts.declinedCardIds(paymentId));
        // 자료만으로 승인할 수 없는 카드는 선점·시도 횟수를 쓰기 전에 거른다(신용카드 결제일·한도 자료 없음 → 422)
        approver.precheck(userId, selection.card(), terms);

        // 출금 실행·결과 저장과 복구가 같은 결제에 겹치지 않게 결제별로 잠근다
        RedisLocks.Lock settleLock = locks.tryLock(WithdrawalRecovery.settleLockKey(paymentId),
                properties.settleLockTtl()).orElseThrow(PaymentApprovalService::inProgress);
        try (settleLock) {
            recheckPreviousWithdrawal(payment);
            if (selection.candidate() == null) {
                // 추천 밖의 카드를 직접 골랐다: 혜택·한도를 쓰지 않으므로 다시 계산하지 않는다
                return execute(payment, terms, recommendation, selection, key, fingerprint);
            }
            // 같은 카드의 한도를 두 결제가 동시에 깎지 않게, 다시 계산 ~ 원장 커밋까지 카드별로 잠근다(Task 5.2.5, 임시 B1)
            RedisLocks.Lock cardLock = locks.tryLock(cardLedgerLockKey(selection.card().userCardId()),
                    properties.settleLockTtl()).orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                            "같은 카드로 다른 결제를 처리하고 있어요. 잠시 후 다시 시도해 주세요."));
            try (cardLock) {
                Selection current = recalculated(payment, terms, selection);
                return execute(payment, terms, recommendation, current, key, fingerprint);
            }
        }
    }

    /** 카드 하나의 혜택 한도·실적을 쓰는 승인이 겹치지 않게 잡는 잠금 키. 정산 잠금 다음에만 잡는다(잠금 순서 고정). */
    static String cardLedgerLockKey(Long userCardId) {
        return CARD_LEDGER_LOCK_PREFIX + userCardId;
    }

    /**
     * 명세 처리 순서 2: 저장된 POS 조건으로 고른 카드의 혜택을 지금 한도 사용량으로 다시 계산한다(추천 뒤 다른 결제가 한도를 썼을 수 있음).
     * 카드 잠금 안에서 부르므로, 앞 결제의 원장이 커밋된 뒤의 사용량을 읽는다. PROCESSING 선점 전이라 실패해도 결제 행은 그대로다.
     *
     * <ul>
     *   <li>다시 계산한 값이 추천과 달라도 그대로 승인하고 응답에 실제 값을 보인다(정책 Q10, 임시 B2).</li>
     *   <li>다시 계산하니 후보가 아니면(혜택 자료 미확인 등) 혜택 없이 승인한다(임시). 결제 가능 여부는 승인 실행이 다시 확인한다.</li>
     *   <li>규칙 기준일은 POS 요청 시각이다(추천과 같은 입력). 추천 당시 규칙 버전 고정은 BE3 엔진에 없다(임시 A1).</li>
     * </ul>
     */
    private Selection recalculated(PaymentTransaction payment, PaymentTerms terms, Selection selection) {
        Long cardId = selection.card().userCardId();
        RecommendationResult fresh;
        try {
            fresh = recommender.recommend(recommendationRequest(payment, terms));
        } catch (RuntimeException e) {
            log.warn("승인 전 혜택 재계산 실패, 승인하지 않음 paymentId={} card=****{}", payment.getId(), selection.card().last4(),
                    e);
            throw recalculationFailed();
        }
        if (fresh == null) {
            log.error("승인 전 혜택 재계산 결과가 비어 있음 paymentId={}", payment.getId());
            throw recalculationFailed();
        }
        Optional<Candidate> again = fresh.candidate(cardId);
        if (again.isEmpty()) {
            log.warn("승인 전 다시 계산하니 후보가 아님, 혜택 없이 승인(임시) paymentId={} card=****{} status={}", payment.getId(),
                    selection.card().last4(), fresh.cardStatus(cardId).map(Enum::name).orElse("MISSING"));
            return new Selection(selection.card(), null, selection.recommendedRank(), selection.userSelected(),
                    fresh.recognition(cardId).orElse(null));
        }
        Candidate candidate = again.get();
        Long before = instantDiscountOrNull(selection.candidate());
        long now;
        try {
            now = candidate.instantDiscount();
        } catch (IllegalStateException e) {
            log.warn("다시 계산한 후보의 즉시할인을 확정할 수 없음 paymentId={} card=****{}", payment.getId(),
                    selection.card().last4(), e);
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 할인 금액을 확정할 수 없어요. 다른 카드를 골라 주세요.",
                    Map.of("reason", "INSTANT_DISCOUNT_UNRESOLVED"));
        }
        if (before == null || before != now
                || candidate.netBenefit().compareTo(selection.candidate().netBenefit()) != 0) {
            log.info("승인 때 다시 계산한 혜택이 추천과 다름(그대로 승인) paymentId={} instantDiscount {}→{} netBenefit {}→{}",
                    payment.getId(), before, now, selection.candidate().netBenefit(), candidate.netBenefit());
        }
        return new Selection(selection.card(), candidate, selection.recommendedRank(), selection.userSelected(),
                fresh.recognition(cardId).orElse(null));
    }

    private static Long instantDiscountOrNull(Candidate candidate) {
        try {
            return candidate.instantDiscount();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static BusinessException recalculationFailed() {
        return new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "혜택을 다시 계산하지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 다른 키로 온 요청이 결과 불명 결제의 복구를 거쳤을 때. 이 요청에서는 새로 출금하지 않는다(재출금은 사용자가 다시 누를 때만).
     *
     * @return 이 요청의 응답. 금융망에 간 적 없어 되돌린 REQUESTED면 비어 있고 이어서 승인한다
     */
    private Optional<PaymentApprovalResponse> afterRecovery(PaymentTransaction payment) {
        return switch (payment.getStatus()) {
            case REQUESTED -> Optional.empty();
            case DECLINED -> Optional.of(rowResponse(payment, null));
            default -> throw byStatus(payment.getStatus());
        };
    }

    /**
     * 출금 미확인으로 거절된 결제를 다시 승인하기 직전에, 앞 요청 번호의 출금이 늦게 처리됐는지 거래내역에서 다시 본다
     * (스키마 사전 채번 규칙: 결과가 불명확하면 기존 거래 상태를 먼저 확인하고 출금을 곧바로 반복하지 않는다).
     * 찾으면 복구가 승인으로 바로잡으므로 이미 처리된 결제(409)다. 확인하지 못하면 이번에는 출금하지 않는다(502).
     */
    private void recheckPreviousWithdrawal(PaymentTransaction payment) {
        if (!payment.isLateConfirmable(payment.getFinRequestNo())) {
            return;
        }
        switch (recovery.recheckBeforeRetry(payment.getId())) {
            case CONFIRMED -> throw byStatus(PaymentStatus.APPROVED);
            case UNDECIDED -> throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE,
                    "이전 출금 결과를 확인하지 못해 아직 다시 결제할 수 없어요. 잠시 후 다시 시도해 주세요.");
            case NOT_FOUND, NOT_APPLICABLE -> { }
        }
    }

    private PaymentApprovalResponse execute(PaymentTransaction payment, PaymentTerms terms,
            RecommendationResult recommendation, Selection selection, String key, String fingerprint) {
        Long paymentId = payment.getId();
        Long userId = payment.getUserId();
        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        // 시도 기록에 승인액을 정한 후보를 남긴다. 결과 불명 출금을 복구가 승인으로 확정할 때 이 값으로 원장을 적는다
        Attempt attempt = start(paymentId, selection, Attempt.start(key, fingerprint, selection.card(),
                selection.recommendedRank(), selection.userSelected(), nowUtc, selection.candidate(), selection.recognition()));
        log.info("결제 승인 시작 paymentId={} attempt={} card=****{}", paymentId, attempt.no(), selection.card().last4());

        ApprovalOutcome outcome = run(new ApprovalCommand(paymentId, userId, terms, selection.card(),
                selection.candidate(), payment.getRequestedAt(), nowUtc, attempt.no(), selection.recognition()), attempt);
        Map<YearMonth, CardBill> bills = ensureBills(paymentId, userId, selection, outcome, attempt);
        PaymentApprovalResponse response = response(payment, selection, recommendation, outcome, attempt.no(),
                nowUtc, List.of(), Map.of(), null);
        Attempt recorded = record(paymentId, attempt, outcome, response);
        Settled settled = settle(paymentId, attempt, outcome, nowUtc, bills);
        PaymentPerformanceTotals.Total total = null;
        if (outcome instanceof ApprovalOutcome.Approved approved) {
            markBarcodeUsed(paymentId);
            total = performanceTotal(selection.card(), approved);
        }
        if (!settled.items().isEmpty() || !settled.paymentBenefitIds().isEmpty() || total != null) {
            // 회차 번호·혜택 행 번호·월 실적 누적(S15P31E103-390)은 tx2가 저장해야 정해지므로, 그 값을 채운 응답으로 시도 기록을
            // 다시 적는다. 같은 키 재전송은 이 응답(승인 직후의 누적)을 돌려준다
            response = response(payment, selection, recommendation, outcome, attempt.no(), nowUtc,
                    billItems(settled.items(), bills), settled.paymentBenefitIds(), total);
            recordSavedNumbers(paymentId, attempt, recorded, response);
        }
        log.info("결제 승인 결과 저장 paymentId={} attempt={} status={} failure={} billItems={}", paymentId, attempt.no(),
                settled.payment().getStatus(), settled.payment().getFailureCode(), settled.items().size());
        return response;
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

    /**
     * 승인할 수 없는 상태를 응답 코드로. 선점 경쟁에서 진 뒤 다시 읽은 REQUESTED·DECLINED도 "처리 중"이다.
     * 사용자가 거절한 REJECTED(S15P31E103-360)는 승인·취소처럼 이미 끝난 결제다.
     */
    private static BusinessException byStatus(PaymentStatus status) {
        return switch (status) {
            case EXPIRED -> new BusinessException(ErrorCode.PAYMENT_EXPIRED);
            case APPROVED, CANCELED, REJECTED -> new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    Map.of("status", status.name()));
            default -> inProgress();
        };
    }

    /** 같은 결제를 다른 요청이 처리하는 중. POS와 같은 IDEMPOTENCY_IN_PROGRESS를 쓰고 문구만 결제에 맞춘다. */
    private static BusinessException inProgress() {
        return new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "결제를 처리하고 있습니다. 잠시 후 결과를 확인해 주세요.");
    }

    private BusinessException retryLimitExceeded() {
        return new BusinessException(ErrorCode.RETRY_LIMIT_EXCEEDED, Map.of("maxAttempts", properties.maxAttempts()));
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
            RecommendationResult result = recommender.recommend(recommendationRequest(payment, terms));
            snapshots.save(payment.getId(), result, posProperties.snapshotTtl());
            log.info("추천 스냅샷이 없어 다시 계산 paymentId={}", payment.getId());
            return result;
        });
    }

    /** 저장된 POS 조건으로 만든 추천 요청. 스냅샷이 없을 때와 승인 전 재계산이 같은 입력을 쓴다. */
    private RecommendationRequest recommendationRequest(PaymentTransaction payment, PaymentTerms terms) {
        Optional<MerchantContextSnapshot> context = MerchantContextSnapshot.of(payment, objectMapper);
        return RecommendationRequest.posPurchase(payment.getId(), payment.getUserId(), terms,
                new MerchantContext(payment.getMerchantId(),
                        context.map(MerchantContextSnapshot::categoryCode).orElse(null),
                        payment.getPaymentChannel().name(),
                        payment.getFacilityType() == null ? null : payment.getFacilityType().name()),
                payment.getRequestedAt());
    }

    /**
     * 승인할 카드. 직접 고른 카드는 소유(404) → 이 결제에서 거절됨(400) → 적격(400) 순으로 본다(명세 POL-013 공통 계약: 소유권 확인을 먼저).
     * 생략하면 이 결제에서 거절된 카드를 뺀 1순위 후보다(임시: 명세는 "1순위 추천", 재승인은 "다른 userCardId").
     *
     * @param declined 이 결제에서 확정 거절된 카드
     */
    private Selection select(Long userId, PaymentApprovalRequest request, PaymentTerms terms,
            RecommendationResult recommendation, Set<Long> declined) {
        if (request.userSelected()) {
            Long cardId = request.userCardId();
            cardQuery.requireRegisteredCard(userId, cardId);
            if (declined.contains(cardId)) {
                throw notEligible(ALREADY_DECLINED, "이 결제에서 거절된 카드예요. 다른 카드를 골라 주세요.");
            }
            ActiveCard card = activeCard(userId, cardId)
                    .orElseThrow(() -> notEligible("CARD_INACTIVE", "추천에서 제외한 카드예요. 카드 설정에서 다시 켜 주세요."));
            requireEligible(card, terms, recommendation);
            Optional<Candidate> candidate = recommendation.candidate(cardId);
            CardSummary summary = candidate.map(Candidate::card).orElseGet(() -> summary(card));
            return new Selection(summary, candidate.orElse(null), candidate.map(Candidate::rank).orElse(null), true,
                    recommendation.recognition(cardId).orElse(null));
        }
        if (!recommendation.isAvailable()) {
            if (recommendation.unavailableReason() == UnavailableReason.DATA_INSUFFICIENT) {
                throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "추천을 확정할 수 없어요. 결제할 카드를 직접 골라 주세요.",
                        Map.of("reason", UnavailableReason.DATA_INSUFFICIENT.name()));
            }
            throw notEligible(UnavailableReason.NO_ELIGIBLE_CARD.name(), "결제할 수 있는 카드가 없어요.");
        }
        Candidate top = recommendation.candidates().stream()
                .filter(c -> !declined.contains(c.card().userCardId()))
                .findFirst()
                .orElseThrow(() -> notEligible(ALREADY_DECLINED, "추천 카드가 모두 거절됐어요. 결제할 카드를 직접 골라 주세요."));
        ActiveCard card = activeCard(userId, top.card().userCardId())
                .orElseThrow(() -> notEligible("CARD_INACTIVE", "1순위 카드를 지금은 쓸 수 없어요. 결제할 카드를 직접 골라 주세요."));
        requireEligible(card, terms, recommendation);
        return new Selection(top.card(), top, top.rank(), false,
                recommendation.recognition(top.card().userCardId()).orElse(null));
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
     * ⑤ 시도 예약 → tx1. 같은 결제의 다른 키 요청은 시작 잠금에서 409로 돌려보낸다.
     * 예약을 tx1 앞에 두어, Redis가 실패하면 결제 행을 바꾸기 전에 끝난다. tx1이 실패하면 예약을 지워 횟수를 쓰지 않는다.
     */
    private Attempt start(Long paymentId, Selection selection, Attempt draft) {
        RedisLocks.Lock startLock = locks.tryLock(attempts.startLockKey(paymentId), properties.startLockTtl())
                .orElseThrow(PaymentApprovalService::inProgress);
        try (startLock) {
            Reservation reservation = attempts.reserve(paymentId, draft);
            switch (reservation.status()) {
                case LIMIT_REACHED -> throw retryLimitExceeded();
                case CONTENDED -> throw inProgress();
                case RESERVED -> { }
            }
            Attempt attempt = reservation.attempt();
            try {
                takeProcessing(paymentId, selection);
            } catch (RuntimeException e) {
                release(paymentId, attempt);
                throw e;
            }
            return attempt;
        }
    }

    private void release(Long paymentId, Attempt attempt) {
        try {
            attempts.release(paymentId, attempt);
        } catch (RuntimeException e) {
            log.warn("선점하지 못한 승인 시도 예약을 지우지 못함(시도 횟수 하나를 씀) paymentId={} attempt={}", paymentId,
                    attempt.no(), e);
        }
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
                payment.startApproval(selection.card().userCardId(), selection.card().cardProductId(),
                        selection.recommendedRank(), selection.userSelected());
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

    /**
     * ⑥ 승인 실행. 결과 불명(금융망 무응답·시간 초과 등)이면 돈이 빠졌을 수 있으므로 PROCESSING에 둔다(복구는 Task 5.2.3).
     * BusinessException이 아닌 예외도 명세대로 502 FIN_API_UNAVAILABLE로 답한다.
     */
    private ApprovalOutcome run(ApprovalCommand command, Attempt attempt) {
        try {
            return approver.approve(command);
        } catch (ApprovalNotSentException e) {
            // 금융망에 간 적이 없다. 결제를 승인 전으로 되돌리고 시도를 지워 다시 시도할 수 있게 한다(시도 횟수에 세지 않음)
            releaseApproval(command.paymentId());
            release(command.paymentId(), attempt);
            log.warn("결제 승인 실행을 보내지 못해 되돌림 paymentId={} attempt={}: {}", command.paymentId(), attempt.no(),
                    e.getMessage());
            // 신용카드 실행 실패는 그 오류로(예: 카드 해지 404), 체크카드 미전송은 502로 답한다
            throw e.reply().orElseGet(() -> new BusinessException(ErrorCode.FIN_API_UNAVAILABLE));
        } catch (BusinessException e) {
            log.warn("결제 승인 실행 실패, PROCESSING 유지 paymentId={} attempt={} code={}", command.paymentId(),
                    attempt.no(), e.getErrorCode(), e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("결제 승인 실행 실패, PROCESSING 유지 paymentId={} attempt={}", command.paymentId(), attempt.no(), e);
            throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
        }
    }

    /**
     * ⑥ 신용카드 승인이면 회차가 걸리는 달의 청구서를 확보한다(별도 트랜잭션, 같은 달은 재사용). 시도 기록보다 먼저 해서,
     * 실패하면 승인·회차가 저장된 것이 없으므로 REQUESTED로 되돌리고 시도를 지운다(시도 횟수에 세지 않음). 업무 오류(청구서 마감
     * 422)는 그 오류로, 나머지는 500으로 답한다. 남을 수 있는 것은 빈 ACCUMULATING 청구서뿐이고 다음 승인이 재사용한다. 서버가 여기서 죽으면 PROCESSING에 남고,
     * 요청 번호가 없으므로 복구가 유예 뒤 REQUESTED로 되돌린다.
     */
    private Map<YearMonth, CardBill> ensureBills(Long paymentId, Long userId, Selection selection,
            ApprovalOutcome outcome, Attempt attempt) {
        if (!(outcome instanceof ApprovalOutcome.Approved approved) || !approved.hasInstallments()) {
            return Map.of();
        }
        try {
            return billing.ensureBills(userId, selection.card().userCardId(), approved.installments());
        } catch (RuntimeException e) {
            releaseApproval(paymentId);
            release(paymentId, attempt);
            log.warn("청구서를 확보하지 못해 승인 전으로 되돌림 paymentId={} attempt={}", paymentId, attempt.no(), e);
            throw e instanceof BusinessException business ? business : new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** ⑦ tx2 전에 실행 결과를 남긴다. 로그는 항상, 시도 기록은 할 수 있을 때. tx2가 실패하면 이것이 복구 근거다. */
    private Attempt record(Long paymentId, Attempt attempt, ApprovalOutcome outcome, PaymentApprovalResponse response) {
        Attempt result;
        if (outcome instanceof ApprovalOutcome.Approved approved) {
            result = attempt.withResult(Attempt.APPROVED, null, approved.finTransactionNo(), approved.chargedAmount(),
                    response).withFinRequestNo(approved.finRequestNo());
        } else {
            ApprovalOutcome.Declined declined = (ApprovalOutcome.Declined) outcome;
            result = attempt.withResult(Attempt.DECLINED, declined.code().name(), null, null, response);
        }
        log.info("결제 승인 실행 결과 paymentId={} attempt={} outcome={} failure={} finRequestNo={} finTransactionNo={} "
                + "chargedAmount={}", paymentId, attempt.no(), result.outcome(), result.failureCode(),
                result.finRequestNo(), result.finTransactionNo(), result.chargedAmount());
        try {
            attempts.recordResult(paymentId, attempt, result);
        } catch (RuntimeException e) {
            log.warn("승인 실행 결과를 시도 기록에 남기지 못함(위 로그가 근거) paymentId={} attempt={}", paymentId,
                    attempt.no(), e);
        }
        return result;
    }

    /**
     * ⑧ 뒤 청구 회차·혜택 행 번호를 채운 응답으로 시도 기록을 다시 적는다. 실패하면 같은 키 재전송은 번호 없는 ⑦의 응답을 돌려준다.
     */
    private void recordSavedNumbers(Long paymentId, Attempt attempt, Attempt recorded,
            PaymentApprovalResponse response) {
        try {
            attempts.recordResult(paymentId, attempt, recorded.withResult(recorded.outcome(), recorded.failureCode(),
                    recorded.finTransactionNo(), recorded.chargedAmount(), response));
        } catch (RuntimeException e) {
            log.warn("청구 회차·혜택 번호를 채운 응답을 시도 기록에 남기지 못함(재전송은 번호 없는 응답) paymentId={} attempt={}",
                    paymentId, attempt.no(), e);
        }
    }

    /**
     * ⑧ tx2: 승인 결과 저장. 승인이면 원장 반영 포트와 신용카드 청구 회차 저장까지 같은 트랜잭션이다
     * (회차의 FK가 결제 행의 카드와 맞아야 하므로 승인 저장 뒤에 넣는다). 실패하면 승인까지 롤백되어 PROCESSING에 남는다.
     */
    private Settled settle(Long paymentId, Attempt attempt, ApprovalOutcome outcome, LocalDateTime now,
            Map<YearMonth, CardBill> bills) {
        try {
            return transaction.execute(status -> {
                PaymentTransaction payment = payments.findById(paymentId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
                if (outcome instanceof ApprovalOutcome.Approved approved) {
                    if (payment.getStatus() == PaymentStatus.PROCESSING && (approved.finRequestNo() == null
                            || approved.finRequestNo().equals(payment.getFinRequestNo()))) {
                        payment.approve(approved.approvedAmount(), approved.recognizedPerformanceAmount(),
                                approved.performanceMonth(), now, approved.finTransactionNo());
                    } else if (approved.finRequestNo() != null && payment.isLateConfirmable(approved.finRequestNo())) {
                        // 정산 잠금이 풀린 사이 복구가 출금 미확인으로 거절·만료했는데 출금은 성공했다. 돈이 나갔으므로 승인으로 바로잡는다
                        log.warn("출금 미확인으로 거절된 결제의 출금 성공이 늦게 도착, 승인으로 바로잡음 paymentId={} finRequestNo={} "
                                + "bankTransactionNo={}", paymentId, approved.finRequestNo(), approved.finTransactionNo());
                        payment.confirmLateWithdrawal(approved.finRequestNo(), approved.approvedAmount(),
                                approved.recognizedPerformanceAmount(), approved.performanceMonth(), now,
                                approved.finTransactionNo());
                    } else {
                        log.error("승인 결과를 저장할 수 없음(출금됐을 수 있음), 운영 확인 paymentId={} tag={} finRequestNo={} "
                                + "bankTransactionNo={} rowStatus={} rowFinRequestNo={}", paymentId,
                                TransferTag.payment(paymentId), approved.finRequestNo(), approved.finTransactionNo(),
                                payment.getStatus(), payment.getFinRequestNo());
                        throw new IllegalStateException("승인 결과를 저장할 수 없는 결제 상태입니다 (결제 " + paymentId + ")");
                    }
                    payments.saveAndFlush(payment);
                    ApprovalLedger.Recorded recorded = ledger.recordApproval(payment, approved);
                    List<BillItem> items = approved.hasInstallments()
                            ? billing.writeItems(payment, approved.installments(), bills)
                            : List.of();
                    return new Settled(payment, items,
                            recorded == null ? Map.of() : recorded.paymentBenefitIds());
                }
                ApprovalOutcome.Declined declined = (ApprovalOutcome.Declined) outcome;
                payment.decline(declined.code().name());
                payments.saveAndFlush(payment);
                return new Settled(payment, List.of(), Map.of());
            });
        } catch (RuntimeException e) {
            String finTransactionNo = outcome instanceof ApprovalOutcome.Approved approved
                    ? approved.finTransactionNo() : null;
            log.error("승인 결과 저장 실패, PROCESSING에 남음(복구 근거는 시도 기록) paymentId={} attempt={} outcome={} "
                    + "finTransactionNo={}", paymentId, attempt.no(), outcome.getClass().getSimpleName(),
                    finTransactionNo, e);
            throw e;
        }
    }

    /** 금융망에 보내지 못한 시도를 없던 일로. PROCESSING이 아니면(다른 처리가 바꿈) 그대로 둔다. */
    private void releaseApproval(Long paymentId) {
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

    /**
     * 복구가 확정한 결제의 응답을 결제 행에서 만든다(승인 또는 출금 미확인 거절). 카드는 행의 카드이고, 같은 키의 시도가 있으면
     * 그 시도의 카드 표시·추천 순위·직접 선택 여부를 쓴다. 시도가 없으면 행에 적힌 순위·직접 선택 여부를 쓰고, 행에도 없으면
     * (칸이 생기기 전 시작한 결제) 추천 스냅샷의 순위를 쓴다. 혜택 줄·실적·한도는 싣지 않는다(임시: 원장에는 적혀 있고 결제 상세로 본다).
     *
     * @param attempt 이 요청 키가 만든 시도. 다른 키의 요청이면 null
     */
    private PaymentApprovalResponse rowResponse(PaymentTransaction payment, Attempt attempt) {
        Long paymentId = payment.getId();
        Long cardId = payment.getUserCardId();
        CardSummary card = attempt != null && attempt.card() != null ? attempt.card() : cardOf(payment);
        Integer rank = attempt != null ? attempt.recommendedRank()
                : payment.getUserSelected() != null ? payment.getRecommendedRank()
                : snapshots.find(paymentId).flatMap(r -> r.candidate(cardId)).map(Candidate::rank).orElse(null);
        boolean userSelected = attempt != null ? attempt.userSelected() : Boolean.TRUE.equals(payment.getUserSelected());
        SelectedCard selected = new SelectedCard(card.userCardId(), nickname(card), masked(card.last4()),
                card.cardType(), rank, userSelected);
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;
        if (payment.getStatus() == PaymentStatus.APPROVED || payment.getStatus() == PaymentStatus.CANCELED) {
            return new PaymentApprovalResponse(paymentId, payment.getStatus(),
                    ApprovalNumbers.of(payment.getApprovedAt(), paymentId), payment.getApprovedAt(), selected,
                    payment.getRequestedAmount(), plan, months, List.of(), payment.getApprovedAmount(), null, List.of(),
                    null, List.of(), null, null, null, List.of());
        }
        FailureCode code = FailureCode.WITHDRAWAL_NOT_CONFIRMED;
        int remaining = Math.max(0, properties.maxAttempts() - attempts.count(paymentId));
        boolean retryable = payment.getStatus() == PaymentStatus.DECLINED && remaining > 0
                && !expiry.isPastDeadline(payment);
        List<SuggestedCard> suggested = retryable
                ? snapshots.find(paymentId).map(r -> suggestions(r, triedCardIds(paymentId, cardId))).orElse(List.of())
                : List.of();
        return new PaymentApprovalResponse(paymentId, payment.getStatus(), null, null, selected,
                payment.getRequestedAmount(), plan, months, List.of(), null, null, List.of(), null, List.of(),
                new Failure(code.name(), code.message(), Map.of()), retryable, remaining, suggested);
    }

    /** 결제 행의 카드 표시 정보. 지금 쓸 수 있는 카드에 없으면(해지·정지) 등록 카드에서 찾는다. */
    private CardSummary cardOf(PaymentTransaction payment) {
        Long userId = payment.getUserId();
        Long cardId = payment.getUserCardId();
        return activeCard(userId, cardId).map(PaymentApprovalService::summary)
                .or(() -> cardQuery.findRegisteredCard(userId, cardId)
                        .map(ref -> new CardSummary(ref.userCardId(), ref.cardProductId(), ref.cardType(), false,
                                null, ref.cardLast4(), null, null)))
                .orElseThrow(() -> new IllegalStateException("결제의 카드를 찾을 수 없습니다 (결제 " + payment.getId() + ")"));
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

    /** 응답 기억. 결제는 이미 저장됐으므로 실패해도 응답은 그대로 돌려준다. 재전송은 시도 기록으로 답한다. */
    private void remember(Long userId, String key, Previous previous) {
        try {
            idempotency.remember(userId, key, previous);
        } catch (RuntimeException e) {
            log.warn("승인 응답 기억 실패(재전송은 시도 기록으로 응답) paymentId={}", previous.paymentId(), e);
        }
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

    /**
     * tx2 전에 만든다. 저장될 값(상태·승인 시각)은 실행 결과와 처리 시각으로 정해지므로 저장 뒤의 행과 같다.
     * 혜택 행 번호와 청구 회차 번호는 tx2 뒤에 다시 만들 때 채운다.
     *
     * @param paymentBenefitIds 혜택 번호 → payment_benefit 행 번호. tx2 전이면 빈 맵(번호 null)
     */
    private PaymentApprovalResponse response(PaymentTransaction payment, Selection selection,
            RecommendationResult recommendation, ApprovalOutcome outcome, int attemptNo, LocalDateTime now,
            List<PaymentApprovalResponse.BillItem> billItems, Map<Long, Long> paymentBenefitIds,
            PaymentPerformanceTotals.Total total) {
        CardSummary card = selection.card();
        SelectedCard selected = new SelectedCard(card.userCardId(), nickname(card), masked(card.last4()),
                card.cardType(), selection.recommendedRank(), selection.userSelected());
        PaymentPlan plan = payment.isInstallment() ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        Integer months = payment.isInstallment() ? payment.getInstallmentMonths() : null;

        if (outcome instanceof ApprovalOutcome.Approved approved) {
            // confirmed: 승인과 함께 반영된 즉시할인만 확정이다. 청구할인·캐시백·적립은 청구·지급 때 확정된다
            List<Benefit> benefits = approved.benefits().stream()
                    .map(b -> new Benefit(paymentBenefitIds.get(b.benefitId()), b.benefitId(), b.name(), b.type(),
                            b.amount(), b.realization(), b.isInstant()))
                    .toList();
            return new PaymentApprovalResponse(payment.getId(), PaymentStatus.APPROVED,
                    ApprovalNumbers.of(now, payment.getId()), now, selected, payment.getRequestedAmount(), plan,
                    months, benefits, approved.chargedAmount(), null, billItems, performance(approved, total),
                    limitImpacts(approved.basis()), null, null, null, List.of());
        }
        ApprovalOutcome.Declined declined = (ApprovalOutcome.Declined) outcome;
        // 예약이 최대 횟수를 넘지 않게 하므로 attemptNo ≤ maxAttempts다
        int remaining = properties.maxAttempts() - attemptNo;
        boolean retryable = remaining > 0 && !expiry.isPastDeadline(payment);
        List<SuggestedCard> suggested = retryable
                ? suggestions(recommendation, triedCardIds(payment.getId(), card.userCardId()))
                : List.of();
        return new PaymentApprovalResponse(payment.getId(), PaymentStatus.DECLINED, null, null, selected,
                payment.getRequestedAmount(), plan, months, List.of(), null, null, List.of(), null, List.of(),
                new Failure(declined.code().name(), declined.message(), declined.details()), retryable, remaining,
                suggested);
    }

    /**
     * 이 승인의 실적 반영. 달성률은 최소 실적 기준을 BE3 결과가 내보내지 않아 null이다(임시).
     * 실적 인정액은 BE3 계산 값이다(Task 5.2.11). 응답 모양은 그대로 두어, 월 실적에 더해졌을 때만 {@code recognized=true}이고
     * 제외(0)·미확인(null)은 {@code recognizedAmount=0, recognized=false}다(임시: 둘을 나눠 보일지는 실적 화면 담당과 정한다).
     *
     * <p>누적·목표(S15P31E103-390)는 원장 저장 뒤에 읽은 값이다. 원장 저장 전 응답, 누적을 읽지 못한 응답, 인정액 미확인 결제는
     * null이다(미확인은 이 결제가 얼마로 잡힐지 몰라 줄을 보이지 않는다).
     */
    private static PaymentApprovalResponse.Performance performance(ApprovalOutcome.Approved approved,
            PaymentPerformanceTotals.Total total) {
        Long amount = approved.recognizedPerformanceAmount();
        long shown = amount == null ? 0 : amount;
        boolean showTotal = amount != null && total != null;
        return new PaymentApprovalResponse.Performance(YearMonth.from(approved.performanceMonth()).toString(), shown,
                shown > 0, null, showTotal ? total.recognizedTotalAfter() : null,
                showTotal ? total.targetAmount() : null);
    }

    /**
     * 승인을 저장한 뒤의 월 실적 누적·목표. 승인은 이미 끝났으므로 읽지 못해도 승인을 실패로 만들지 않고 null로 둔다.
     * 인정액 미확인 결제는 줄을 보이지 않으므로 읽지 않는다.
     */
    private PaymentPerformanceTotals.Total performanceTotal(CardSummary card, ApprovalOutcome.Approved approved) {
        if (approved.recognizedPerformanceAmount() == null || approved.performanceMonth() == null) {
            return null;
        }
        try {
            return performanceTotals.of(card.userCardId(), card.cardProductId(), approved.performanceMonth());
        } catch (RuntimeException e) {
            log.warn("승인 뒤 월 실적 누적을 읽지 못함(응답의 누적·목표 null) userCardId={}", card.userCardId(), e);
            return null;
        }
    }

    /**
     * 이 승인이 쓴 한도(원장의 payment_limit_usage와 같은 값). 사용량이 0인 그룹은 뺀다.
     * 남은 한도 = 사용 직전 남은 한도 − 사용량이다.
     */
    private static List<PaymentApprovalResponse.LimitImpact> limitImpacts(Candidate basis) {
        if (basis == null) {
            return List.of();
        }
        return basis.expectedLimitUses().stream()
                .filter(use -> use.consumed().signum() > 0)
                .map(use -> new PaymentApprovalResponse.LimitImpact(use.limitGroupId(), use.period(),
                        use.consumed().longValue(), use.remainingBefore().subtract(use.consumed()).max(
                                java.math.BigDecimal.ZERO).longValue()))
                .toList();
    }

    /** 실행 결과를 저장하기 전에 읽으므로 Redis가 실패해도 저장을 막지 않는다. 그때는 이번 카드만 뺀다. */
    private Set<Long> triedCardIds(Long paymentId, Long current) {
        try {
            return attempts.triedCardIds(paymentId);
        } catch (RuntimeException e) {
            log.warn("시도한 카드를 읽지 못해 이번 카드만 추천에서 뺌 paymentId={}", paymentId, e);
            return Set.of(current);
        }
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

    /**
     * 기억된 응답 없이 시도 기록에서 찾은 이 키의 시도. 실행 결과가 DB에 저장된 뒤면 그때 만든 응답을, 아니면(실행 중·결과 불명·tx2 실패) 409다.
     * 결과 불명이 복구로 REQUESTED가 됐어도 이 키는 다시 실행하지 않는다. 새 시도는 새 키로 한다.
     */
    private PaymentApprovalResponse replayAttempt(Attempt attempt, String fingerprint, PaymentTransaction payment) {
        if (!attempt.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (attempt.response() != null && isSaved(attempt, payment)) {
            log.info("같은 Idempotency-Key 재전송을 시도 기록으로 응답 paymentId={} attempt={}", payment.getId(), attempt.no());
            return attempt.response();
        }
        if (isRecovered(attempt, payment)) {
            log.info("같은 Idempotency-Key 재전송을 복구된 결제 행으로 응답 paymentId={} attempt={}", payment.getId(),
                    attempt.no());
            return rowResponse(payment, attempt);
        }
        throw inProgress();
    }

    /**
     * 결과 불명이던 이 시도를 복구가 확정했는지(승인, 또는 출금 미확인 거절·만료). 같은 출금 요청 번호로 대조한다.
     * 시도 기록에 요청 번호가 없으면(기록 실패) 마지막 시도이고 카드가 같을 때만 같은 시도로 본다.
     */
    private boolean isRecovered(Attempt attempt, PaymentTransaction payment) {
        PaymentStatus status = payment.getStatus();
        boolean settled = status == PaymentStatus.APPROVED || status == PaymentStatus.CANCELED
                || payment.isLateConfirmable(payment.getFinRequestNo());
        if (!settled) {
            return false;
        }
        if (attempt.finRequestNo() != null) {
            return attempt.finRequestNo().equals(payment.getFinRequestNo());
        }
        return attempt.no() == attempts.count(payment.getId()) && attempt.card() != null
                && attempt.card().userCardId().equals(payment.getUserCardId());
    }

    /**
     * 시도의 실행 결과가 DB에 저장됐는지. 승인은 지금 행이 승인(뒤 취소 포함)이고, 거절은 뒤 시도가 있거나 행이 거절·만료다.
     * 거절 뒤 사용자가 결제 요청을 거절(REJECTED, S15P31E103-360)한 행도 그 거절이 저장된 뒤다.
     */
    private boolean isSaved(Attempt attempt, PaymentTransaction payment) {
        PaymentStatus status = payment.getStatus();
        if (Attempt.APPROVED.equals(attempt.outcome())) {
            return status == PaymentStatus.APPROVED || status == PaymentStatus.CANCELED;
        }
        return attempt.no() < attempts.count(payment.getId())
                || status == PaymentStatus.DECLINED || status == PaymentStatus.EXPIRED
                || status == PaymentStatus.REJECTED;
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

    /**
     * 승인할 카드와 추천 근거.
     *
     * @param candidate       추천 후보였으면 그 후보. 추천 밖의 카드를 직접 골랐으면 null
     * @param recommendedRank 추천 순위. 후보가 아니면 null
     * @param recognition     이 카드의 실적 인정 값(BE3 계산, Task 5.2.11). 혜택을 다시 계산했으면 그 결과의 값이다. 없으면 null
     */
    private record Selection(CardSummary card, Candidate candidate, Integer recommendedRank, boolean userSelected,
            PerformanceRecognition recognition) {}

    /** tx2가 저장한 것: 결제 행, 신용카드면 청구 회차, 원장에 적은 혜택 행 번호(혜택 번호 → payment_benefit 번호). */
    private record Settled(PaymentTransaction payment, List<BillItem> items, Map<Long, Long> paymentBenefitIds) {}

    /** 선점 트랜잭션 안에서 마감을 발견했을 때 롤백하고 밖에서 만료 처리하기 위한 신호. */
    private static final class DeadlinePassed extends RuntimeException {
        DeadlinePassed() {
            super("결제 확정 마감이 지났습니다.", null, false, false);
        }
    }
}
