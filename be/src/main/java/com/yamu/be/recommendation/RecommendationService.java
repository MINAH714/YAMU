package com.yamu.be.recommendation;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.RecommendationPerformanceAssembler;
import com.yamu.be.card.RecommendationPerformanceAssembler.Recognition;
import com.yamu.be.card.domain.*;
import com.yamu.be.recommendation.RecommendationCommand.PaymentAvailability;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.time.YearMonth;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 같은 BE의 추천 내부 진입점. DatabaseCardRecommender가 결제 포트 입력을 변환해서 호출한다. */
@Service
@Slf4j
public class RecommendationService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final UserCardRepository cards;
    private final CardProductRepository products;
    private final RecommendationPolicyLoader policies;
    private final RecommendationInputAssembler assembler;
    private final CardActivityReader activity;
    private final RecommendationPerformanceAssembler performance;
    private final RecommendationRecognitionResolver recognitionResolver;
    private final PurchaseItemReader purchaseItems;
    private final RecommendationAvailabilityReader availabilityReader;
    private final TransactionTemplate read;
    private final RecommendationSelector selector = new RecommendationSelector();

    public RecommendationService(UserCardRepository cards, CardProductRepository products,
            RecommendationPolicyLoader policies, RecommendationInputAssembler assembler,
            CardActivityReader activity, RecommendationPerformanceAssembler performance,
            RecommendationRecognitionResolver recognitionResolver, PurchaseItemReader purchaseItems,
            PlatformTransactionManager transactions, RecommendationAvailabilityReader availabilityReader) {
        this.cards = cards;
        this.products = products;
        this.policies = policies;
        this.assembler = assembler;
        this.activity = activity;
        this.performance = performance;
        this.recognitionResolver = recognitionResolver;
        this.purchaseItems = purchaseItems;
        this.availabilityReader=availabilityReader;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        // 호출 측 승인 트랜잭션을 읽기 실패로 rollback-only 상태로 만들지 않는다.
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 결과 변환에서 재사용할 표시 정보만 남긴다. 암호문·해시는 반환하지 않는다. */
    public record CardContext(long cardProductId, String productName, String alias, String last4,
                              com.yamu.be.card.domain.CardType cardType, String issuerCode, boolean defaultCard,
                              Map<Long, String> benefitTiming) { }

    public record Result(RecommendationSelector.Result recommendation, List<RecommendationDataIssue> issues,
                         Map<Long, CardContext> cards, List<SimulationRule> simulationRules) {
        public Result(RecommendationSelector.Result recommendation,List<RecommendationDataIssue> issues,Map<Long,CardContext> cards) {
            this(recommendation,issues,cards,List.of());
        }
        public Result {
            issues = List.copyOf(issues);
            cards = Map.copyOf(cards);
            simulationRules=List.copyOf(simulationRules);
        }
    }

    /**
     * 복구 가능한 DB 실패와 정책 미등록을 구분하며 자동 재조회는 하지 않는다.
     * 읽기 트랜잭션 밖에서 실패를 처리해 rollback-only 트랜잭션을 계속 사용하지 않는다.
     */
    public Result recommend(RecommendationCommand command) {
        Objects.requireNonNull(command);
        var state=availabilityReader.prepare(command.paymentId(),command.userId(),command.transaction().amount(),command.transaction().plan());
        return recommendPrepared(command,state);
    }

    /** POS 저장 트랜잭션 전에 서버 상태를 확보한다. 반환 함수는 같은 사용자와 결제 조건에만 쓸 수 있다. */
    public Function<RecommendationCommand,Result> prepare(long userId,long principal,Plan plan) {
        var state=availabilityReader.prepare(null,userId,principal,plan);
        return command -> {
            if(command.userId()!=userId || command.transaction().amount()!=principal || command.transaction().plan()!=plan)
                throw new IllegalArgumentException("준비한 추천의 사용자 또는 결제 조건이 다릅니다.");
            return recommendPrepared(command,state);
        };
    }

    private Result recommendPrepared(RecommendationCommand supplied,RecommendationAvailabilityReader.Assessment state) {
        // 호환 입력의 카드 소유권은 검사하되 전달된 AVAILABLE 값으로 서버 판정을 덮어쓰지 않는다.
        if(!state.cards().keySet().containsAll(supplied.availability().keySet()))
            throw new IllegalArgumentException("사용자 보유 카드 외의 결제 가능 판정입니다.");
        var command=new RecommendationCommand(supplied.paymentId(),supplied.userId(),supplied.transaction(),state.cards());
        var loaded = new Loaded();
        Prepared prepared = null;
        PolicyRows rows = null;
        try {
            rows = Objects.requireNonNull(read.execute(status -> {
                var result = load(command, loaded, supplied.availability().keySet());
                loaded.stage = "INPUT_READ_TRANSACTION_COMPLETION";
                loaded.affectedCardIds = loaded.cards.stream().map(UserCard::getId).toList();
                return result;
            }));
        } catch (TransientDataAccessException failure) {
            logReadFailure(command, loaded, "DATABASE_READ_RETRYABLE", failure);
            if (loaded.cards == null) {
                // 후보 목록조차 모르면 빈 카드 목록을 정상 NO_ELIGIBLE_CARD로 반환할 수 없다.
                throw new RecommendationReadException(command.paymentId());
            }
            var issues = loaded.cards.stream().filter(this::needsBenefit)
                    .map(c -> new RecommendationDataIssue(c.getId(), null, "DATABASE_READ_RETRYABLE", true)).toList();
            if (issues.isEmpty()) {
                issues = List.of(new RecommendationDataIssue(null, null, "DATABASE_READ_RETRYABLE", true));
            }
            loaded.recognitions = null;
            prepared = new Prepared(input(command, loaded, Map.of(), Map.of()), issues);
        }
        if (rows != null) {
            var issues = new ArrayList<RecommendationDataIssue>();
            Map<Long, Long> previous = Map.of();
            Set<Long> failedPrevious = Set.of();
            if (!rows.priorIds().isEmpty()) {
                loaded.stage = "PREVIOUS_PERFORMANCE_BATCH";
                loaded.affectedCardIds = rows.priorIds();
                var priorIds = rows.priorIds();
                var month = YearMonth.from(rows.date()).minusMonths(1);
                try {
                    previous = Objects.requireNonNull(read.execute(status -> {
                        var amounts = activity.recognizedAmounts(priorIds, month);
                        loaded.stage = "PREVIOUS_PERFORMANCE_TRANSACTION_COMPLETION";
                        return amounts;
                    }));
                } catch (TransientDataAccessException failure) {
                    // execute 밖에서 처리한다. 종료 실패 시에도 조회한 값을 채택하지 않는다.
                    logReadFailure(command, loaded, "DATABASE_READ_RETRYABLE", failure);
                    failedPrevious = Set.copyOf(priorIds);
                    priorIds.forEach(id -> issues.add(new RecommendationDataIssue(id, null, "DATABASE_READ_RETRYABLE", true)));
                }
            }
            prepared = assemble(command, loaded, rows, previous, failedPrevious, issues);
        }
        Objects.requireNonNull(prepared);
        if (loaded.recognitions != null) {
            Map<Long, PerformanceProgress> progress;
            var issues = new ArrayList<>(prepared.issues());
            try {
                loaded.stage = "TIE_PERFORMANCE_BATCH";
                loaded.affectedCardIds = loaded.recognitions.stream().filter(r -> r.period() != null)
                        .map(Recognition::userCardId).toList();
                progress = loaded.recognitions.stream().anyMatch(r -> r.period() != null)
                        ? Objects.requireNonNull(read.execute(status -> {
                            var result = performance.assemble(loaded.recognitions);
                            loaded.stage = "TIE_READ_TRANSACTION_COMPLETION";
                            return result;
                        }))
                        : performance.assemble(loaded.recognitions);
                progress.forEach((id, value) -> {
                    if (value.period() != null && value.beforeAmount() == null) {
                        issues.add(new RecommendationDataIssue(id, null, "RECOGNITION_BEFORE_AMOUNT_MISSING", false));
                    }
                });
            } catch (TransientDataAccessException failure) {
                logReadFailure(command, loaded, "RECOGNITION_AGGREGATE_READ_RETRYABLE", failure);
                // 동률 누적액 조회 실패는 이미 확보한 혜택 정책을 지우지 않는다.
                progress = new HashMap<>();
                for (var recognition : loaded.recognitions) {
                    progress.put(recognition.userCardId(), new PerformanceProgress(recognition.period(), null,
                            recognition.minimumAmount(), recognition.recognizedAmount()));
                    if (recognition.period() != null) {
                        issues.add(new RecommendationDataIssue(recognition.userCardId(), null,
                                "RECOGNITION_AGGREGATE_READ_RETRYABLE", true));
                    }
                }
            }
            prepared = new Prepared(input(command, loaded, loaded.policies, progress), issues);
        }
        // 트랜잭션이 끝난 뒤 순수 계산만 실행한다. 읽기 실패가 정상적인 정책 미등록으로 바뀌지 않는다.
        var rules=new ArrayList<>(command.transaction().simulationRules());
        loaded.policies.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rules.addAll(e.getValue().simulationRules()));
        var combinedIssues=new ArrayList<>(prepared.issues());
        combinedIssues.addAll(state.issues());
        return new Result(selector.select(prepared.input()), combinedIssues, context(loaded), rules);
    }

    private PolicyRows load(RecommendationCommand command, Loaded loaded, Set<Long> suppliedCardIds) {
        loaded.cards = cards.findOrdered(command.userId(), List.of(CardRegistrationStatus.ACTIVE, CardRegistrationStatus.INACTIVE));
        if (loaded.cards.stream().anyMatch(c -> !c.getUserId().equals(command.userId()))) {
            throw new IllegalArgumentException("다른 사용자의 카드는 추천할 수 없습니다.");
        }
        var ownedIds = loaded.cards.stream().map(UserCard::getId).collect(Collectors.toSet());
        if (!ownedIds.containsAll(suppliedCardIds)) {
            throw new IllegalArgumentException("조회된 사용자 등록 카드 외의 결제 가능 판정을 전달할 수 없습니다.");
        }
        loaded.stage = "PURCHASE_ITEM";
        loaded.affectedCardIds = loaded.cards.stream().map(UserCard::getId).toList();
        purchaseItems.validateStored(command.transaction().purchaseItemCode());
        // 상품을 inner join해서 상품 누락 카드가 사라지지 않게 한다.
        var ids = loaded.cards.stream().map(UserCard::getCardProductId).distinct().toList();
        loaded.stage = "PRODUCT_BATCH";
        loaded.affectedCardIds = loaded.cards.stream().map(UserCard::getId).toList();
        loaded.products = ids.isEmpty() ? Map.of() : products.findAllById(ids).stream()
                .collect(Collectors.toMap(CardProduct::getId, Function.identity()));
        var date = command.transaction().occurredAt().atZone(SEOUL).toLocalDate();
        var eligible = loaded.cards.stream().filter(this::needsBenefit)
                .filter(c -> loaded.products.containsKey(c.getCardProductId()))
                .filter(c -> availability(command, c).status() == Availability.AVAILABLE)
                .filter(c -> command.transaction().plan() != Plan.INSTALLMENT
                        || loaded.products.get(c.getCardProductId()).getCardType() != com.yamu.be.card.domain.CardType.CHECK)
                .toList();
        loaded.stage = "POLICY_AND_USAGE_BATCH";
        loaded.affectedCardIds = eligible.stream().map(UserCard::getId).toList();
        var data = policies.load(command.userId(), eligible, date);
        data.benefits().stream().filter(b -> b.getApplicationTiming() != null)
                .forEach(b -> loaded.benefitTiming.put(b.getId(), b.getApplicationTiming()));
        // min_performance_amount의 저장 의미는 '최소 전월 실적'이다. 서버 현재 월은 사용하지 않는다.
        // 실적 조회 성공만으로 상품 정책을 확인 완료로 승격하지 않는다.
        var priorIds = eligible.stream().filter(c -> data.benefits().stream().anyMatch(b ->
                b.getCardProductId().equals(c.getCardProductId()) && b.getMinPerformanceAmount() > 0)
                || data.links().stream().anyMatch(l -> l.getCardProductId().equals(c.getCardProductId())
                && data.tiers().stream().anyMatch(t -> t.getLimitGroupId().equals(l.getLimitGroupId()))))
                .map(UserCard::getId).toList();
        return new PolicyRows(data, eligible, priorIds, date);
    }

    private Prepared assemble(RecommendationCommand command, Loaded loaded, PolicyRows rows,
            Map<Long, Long> previous, Set<Long> failedPrevious, List<RecommendationDataIssue> issues) {
        var policyByCard = new HashMap<Long, BenefitPolicy>();
        var recognitions = new ArrayList<Recognition>();
        for (var card : rows.eligible()) {
            if (failedPrevious.contains(card.getId())) {
                // 불필요한 카드에는 실패를 전파하지 않고, 의존 카드에는 null/0 실적 정책을 조립하지 않는다.
                continue;
            }
            if (rows.priorIds().contains(card.getId()) && !previous.containsKey(card.getId())) {
                issues.add(new RecommendationDataIssue(card.getId(), null, "PREVIOUS_PERFORMANCE_MISSING", false));
            }
            try {
                policies.validateFallbacks(card, rows.data());
                policyByCard.put(card.getId(), assembler.policy(card, rows.data(), rows.date(), previous.get(card.getId()), issues,
                        command.transaction(),loaded.products.get(card.getCardProductId())));
            } catch (IllegalArgumentException invalidPolicy) {
                // DB의 개별 필드 제약만으로 보장되지 않는 정책 조합 오류도 해당 카드에 한정한다.
                issues.add(new RecommendationDataIssue(card.getId(), null, "POLICY_DATA_INVALID", false));
            }
            recognitions.add(recognitionResolver.resolve(card.getId(), loaded.products.get(card.getCardProductId()),
                    command.transaction(), policyByCard.get(card.getId()), issues));
        }
        loaded.recognitions = List.copyOf(recognitions);
        loaded.policies = Map.copyOf(policyByCard);
        loaded.cards.stream().filter(c -> !loaded.products.containsKey(c.getCardProductId()))
                .forEach(c -> issues.add(new RecommendationDataIssue(c.getId(), null, "PRODUCT_MISSING", false)));
        return new Prepared(input(command, loaded, policyByCard, Map.of()), issues);
    }

    private RecommendationInput input(RecommendationCommand command, Loaded loaded, Map<Long, BenefitPolicy> policies,
            Map<Long, PerformanceProgress> progress) {
        Long defaultId = null;
        var candidates = new ArrayList<CardCandidate>();
        for (var card : loaded.cards) {
            if (!card.getUserId().equals(command.userId())) {
                throw new IllegalArgumentException("다른 사용자의 카드는 추천할 수 없습니다.");
            }
            if (card.isDefaultCard()) {
                if (defaultId != null) {
                    throw new IllegalStateException("기본 카드가 중복되었습니다.");
                }
                defaultId = card.getId();
            }
            var product = loaded.products.get(card.getCardProductId());
            var available = availability(command, card);
            candidates.add(new CardCandidate(card.getId(), card.getUserId(), product == null ? null
                    : RecommendationInput.CardType.valueOf(product.getCardType().name()), card.getRegistrationStatus(),
                    card.getIssuerStatus(), available.status(), available.reason(), policies.get(card.getId()),
                    progress.get(card.getId()), card.getDisplayOrder(), card.getRegisteredAt()));
        }
        return new RecommendationInput(command.paymentId(), command.userId(), command.transaction(), defaultId, candidates);
    }

    private boolean needsBenefit(UserCard card) {
        return card.getRegistrationStatus() == CardRegistrationStatus.ACTIVE && card.getIssuerStatus() == CardIssuerStatus.NORMAL;
    }

    private PaymentAvailability availability(RecommendationCommand command, UserCard card) {
        return command.availability().getOrDefault(card.getId(),
                new PaymentAvailability(Availability.UNKNOWN, "PAYMENT_AVAILABILITY_UNKNOWN"));
    }

    private Map<Long, CardContext> context(Loaded loaded) {
        var result = new LinkedHashMap<Long, CardContext>();
        for (var card : loaded.cards) {
            var product = loaded.products.get(card.getCardProductId());
            result.put(card.getId(), new CardContext(card.getCardProductId(), product == null ? null : product.getProductName(),
                    card.getCardAlias(), card.getCardLast4(), product == null ? null : product.getCardType(),
                    product == null ? null : product.getIssuerCode(), card.isDefaultCard(), Map.copyOf(loaded.benefitTiming)));
        }
        return result;
    }

    private static final class Loaded {
        private String stage = "CANDIDATE_BATCH";
        private List<Long> affectedCardIds = List.of();
        private List<UserCard> cards;
        private Map<Long, CardProduct> products = Map.of();
        private List<Recognition> recognitions;
        private Map<Long, BenefitPolicy> policies = Map.of();
        private final Map<Long, String> benefitTiming = new HashMap<>();
    }

    private record Prepared(RecommendationInput input, List<RecommendationDataIssue> issues) { }

    private record PolicyRows(RecommendationPolicyData data, List<UserCard> eligible, List<Long> priorIds,
                              LocalDate date) { }

    private void logReadFailure(RecommendationCommand command, Loaded loaded, String code,
            TransientDataAccessException failure) {
        // Throwable 자체·메시지·원인을 로깅하지 않는다. SQL/바인딩값/접속 정보가 포함될 수 있다.
        log.warn("recommendation_db_read_failed paymentId={} stage={} scope={} cardIds={} code={} exceptionType={} retryable=true",
                command.paymentId(), loaded.stage, loaded.cards == null ? "UNKNOWN_CANDIDATES" : "BATCH_POTENTIALLY_AFFECTED",
                loaded.affectedCardIds, code, failure.getClass().getName());
    }

    public static final class RecommendationReadException extends RuntimeException {
        private final Long paymentId;

        public RecommendationReadException(Long paymentId) {
            super(RecommendationDataIssue.RETRY_MESSAGE);
            this.paymentId = paymentId;
        }

        public Long paymentId() { return paymentId; }
        public String code() { return "DATABASE_READ_RETRYABLE"; }
        public boolean retryable() { return true; }
    }
}
