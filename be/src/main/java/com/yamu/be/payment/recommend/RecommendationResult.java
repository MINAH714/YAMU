package com.yamu.be.payment.recommend;

import com.yamu.be.card.domain.CardType;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 추천 결과. 명세의 내부 결과 예시를 따른다. 앱 응답(바코드 조회·결제 상세)은 이 값에서 필요한 부분만 꺼내 만든다.
 *
 * <p>카드는 아래 셋 중 한 곳에만 있다. 이름은 BE3 엔진(!63)의 {@code CardStatus}와 맞췄다.
 * <ul>
 *   <li>{@code candidates}(CALCULATED): 결제 가능과 혜택이 모두 확인된 카드. 순혜택 0원도 "확인된 0원"으로 여기 있다</li>
 *   <li>{@code excludedCards}(EXCLUDED): 확인된 사유로 결제할 수 없는 카드</li>
 *   <li>{@code uncomparedCards}(NOT_COMPARED): 비교하지 못한 카드. 혜택만 미확인인지, 결제 가능 여부가 미확인인지를 사유로 나눈다</li>
 * </ul>
 *
 * <p>정책 변경(2026-11-02, 명세 반영 전): 혜택 비교 가능 여부와 결제 가능 여부를 나눈다. 혜택만 미확인인 카드는 0원 후보로 순위에 넣지 않지만,
 * 사용자가 직접 고르면 확인 문구({@link #NO_BENEFIT_NOTICE})를 보고 혜택 없이 원금으로 결제할 수 있다. 결제 가능 여부가 미확인인 카드는 그 경로를 쓰지 않는다.
 * 어느 경우든 승인은 결제 가능 조건(소유·상태·미납·신용 가용 한도·체크카드 잔액·할부)을 다시 확인한다.
 * 미비교 목록에 있다는 것만으로 막거나, 제외 목록에 없다는 것만으로 승인하지 않는다.
 *
 * @param recommendationId 추천 식별자(예: rec_7c1e5a1b2c3d). 결제 진행 중 Redis에 둘 스냅샷의 이름
 * @param calculatedAt     계산 시각(UTC)
 * @param estimate         잔액 조회 실패 등으로 캐시 값으로 계산했으면 true
 * @param candidates       순혜택 내림차순 후보. 1순위가 첫 번째이고 순위는 1부터 빈틈없이 매긴다
 * @param excludedCards    확인된 사유로 결제할 수 없어 뺀 카드와 사유
 * @param uncomparedCards  비교하지 못한 카드와 사유. 순위가 없다. 이 칸이 생기기 전 스냅샷(JSON)에서 읽으면 빈 목록이다
 * @param recognitions     카드별 이번 거래의 실적 인정 값(BE3 계산 그대로, Task 5.2.11). 후보가 아닌 카드도 계산됐으면 있다.
 *                         계산하지 못한 카드와 이 칸이 생기기 전 스냅샷(JSON)에는 없다(승인은 미확인으로 저장)
 */
public record RecommendationResult(String recommendationId, LocalDateTime calculatedAt, boolean estimate,
        List<Candidate> candidates, List<ExcludedCard> excludedCards, List<UncomparedCard> uncomparedCards, int schemaVersion,
        List<com.yamu.be.recommendation.RecommendationDataIssue> issues,
        List<com.yamu.be.recommendation.SimulationRule> simulationRules, List<PerformanceRecognition> recognitions) {
    public RecommendationResult(String recommendationId, LocalDateTime calculatedAt, boolean estimate,
            List<Candidate> candidates, List<ExcludedCard> excludedCards, List<UncomparedCard> uncomparedCards, int schemaVersion,
            List<com.yamu.be.recommendation.RecommendationDataIssue> issues,
            List<com.yamu.be.recommendation.SimulationRule> simulationRules) {
        this(recommendationId, calculatedAt, estimate, candidates, excludedCards, uncomparedCards, schemaVersion, issues,
                simulationRules, List.of());
    }
    public RecommendationResult(String recommendationId,LocalDateTime calculatedAt,boolean estimate,List<Candidate> candidates,
            List<ExcludedCard> excludedCards,List<UncomparedCard> uncomparedCards,int schemaVersion,
            List<com.yamu.be.recommendation.RecommendationDataIssue> issues) {
        this(recommendationId,calculatedAt,estimate,candidates,excludedCards,uncomparedCards,schemaVersion,issues,List.of());
    }
    public RecommendationResult(String recommendationId, LocalDateTime calculatedAt, boolean estimate,
            List<Candidate> candidates, List<ExcludedCard> excludedCards, List<UncomparedCard> uncomparedCards) {
        this(recommendationId, calculatedAt, estimate, candidates, excludedCards, uncomparedCards, 1, List.of());
    }

    /** 혜택만 미확인인 카드를 사용자가 직접 골랐을 때 승인 전에 보여 주고 확인받는 문구(2026-11-02 정책 변경). */
    public static final String NO_BENEFIT_NOTICE = "혜택 정보를 확인할 수 없어 혜택 없이 결제됩니다.";

    public RecommendationResult {
        simulationRules=simulationRules==null ? List.of() : List.copyOf(simulationRules);
        recognitions = recognitions == null ? List.of() : List.copyOf(recognitions);
        if (recognitions.stream().map(PerformanceRecognition::userCardId).distinct().count() != recognitions.size()) {
            throw new IllegalArgumentException("한 카드의 실적 인정 값은 하나다");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
        // 구형 진행 중 스냅샷 읽기 호환. 신규 DB 보존은 codec에서 버전 필수를 검사한다.
        if (schemaVersion == 0) schemaVersion = 1;
        if (schemaVersion != 1) throw new IllegalArgumentException("지원하지 않는 추천 schemaVersion");
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        candidates = List.copyOf(candidates);
        excludedCards = List.copyOf(excludedCards);
        uncomparedCards = uncomparedCards == null ? List.of() : List.copyOf(uncomparedCards);
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).rank() != i + 1) {
                throw new IllegalArgumentException("후보 순위는 1부터 차례로 매긴다: " + (i + 1) + "번째 후보의 순위가 "
                        + candidates.get(i).rank());
            }
        }
        Set<Long> seen = new HashSet<>();
        Stream.of(candidates.stream().map(Candidate::card), excludedCards.stream().map(ExcludedCard::card),
                        uncomparedCards.stream().map(UncomparedCard::card))
                .flatMap(cards -> cards)
                .forEach(card -> {
                    if (!seen.add(card.userCardId())) {
                        throw new IllegalArgumentException("한 카드는 후보·제외·미비교 중 한 곳에만 있다: " + card.userCardId());
                    }
                });
    }

    /** 추천 상태. 후보·미비교 목록으로 정해지므로 따로 저장하지 않는다(판정은 !63 엔진과 같다). */
    public Status status() {
        if (candidates.isEmpty()) {
            return uncomparedCards.isEmpty() ? Status.NO_ELIGIBLE_CARD : Status.DATA_INSUFFICIENT;
        }
        return uncomparedCards.isEmpty() && candidates.stream().noneMatch(c -> issues.stream().anyMatch(i ->
                Objects.equals(i.userCardId(),c.card().userCardId())
                && com.yamu.be.recommendation.RecommendationDataIssue.IBK_CONFIRMED_ONLY.equals(i.code())))
                ? Status.READY : Status.PARTIAL_READY;
    }

    /** 일부 혜택만 확인된 후보의 안내. 확정 금액 0원과 미확정 추가 혜택도 구분한다. */
    public String benefitNotice(long userCardId) {
        return issues.stream().filter(i -> Objects.equals(i.userCardId(), userCardId)
                && Set.of(com.yamu.be.recommendation.RecommendationDataIssue.IBK_CONFIRMED_ONLY,
                    com.yamu.be.recommendation.RecommendationDataIssue.IBK_ORIGINAL_CASHBACK).contains(i.code()))
                .map(com.yamu.be.recommendation.RecommendationDataIssue::message).findFirst().orElse(null);
    }

    /** 1순위 추천이 있는지(READY·PARTIAL_READY). 카드를 생략한 승인이 자동으로 고를 수 있는지와 같다. */
    public boolean isAvailable() {
        return !candidates.isEmpty();
    }

    /** 1순위가 없는 이유. 1순위가 있으면 null. {@link #status()}의 DATA_INSUFFICIENT·NO_ELIGIBLE_CARD와 같다. */
    public UnavailableReason unavailableReason() {
        return switch (status()) {
            case READY, PARTIAL_READY -> null;
            case DATA_INSUFFICIENT -> UnavailableReason.DATA_INSUFFICIENT;
            case NO_ELIGIBLE_CARD -> UnavailableReason.NO_ELIGIBLE_CARD;
        };
    }

    /** 확인된 후보의 이번 결제 순혜택이 모두 0원인지(!63의 noBenefit). 미비교 카드는 0원으로 세지 않는다. 후보가 없으면 false. */
    public boolean noBenefit() {
        return !candidates.isEmpty() && candidates.stream().allMatch(c -> c.netBenefit().signum() == 0);
    }

    public Optional<Candidate> topCandidate() {
        return candidates.stream().findFirst();
    }

    public Optional<Candidate> candidate(Long userCardId) {
        return candidates.stream().filter(c -> c.card().userCardId().equals(userCardId)).findFirst();
    }

    /** 카드의 실적 인정 값. 계산되지 않았으면 비어 있다(승인은 인정액 미확인으로 저장한다). */
    public Optional<PerformanceRecognition> recognition(Long userCardId) {
        return recognitions.stream().filter(r -> r.userCardId().equals(userCardId)).findFirst();
    }

    /**
     * 카드 한 장의 이번 거래 실적 인정 값. BE3 {@code RecommendationRecognitionResolver}가 상품 규칙과 거래 사실로 계산한 값을
     * 그대로 옮긴다(결제가 다시 계산하지 않는다).
     *
     * @param month  실적을 더할 월. 상품에 인정월 규칙이 없거나(최소 실적 0 상품 포함) 사실이 모자라 정하지 못했으면 null
     * @param amount 실적 인정액(원). 0은 약관상 실적 제외 확정, 양수는 인정, null은 미확인·해당 없음
     */
    public record PerformanceRecognition(Long userCardId, java.time.YearMonth month, Long amount) {
        public PerformanceRecognition {
            Objects.requireNonNull(userCardId, "userCardId");
            if (amount != null && amount < 0) {
                throw new IllegalArgumentException("실적 인정액은 0 이상이다");
            }
        }
    }

    public Optional<ExcludedCard> excluded(Long userCardId) {
        return excludedCards.stream().filter(e -> e.card().userCardId().equals(userCardId)).findFirst();
    }

    public Optional<UncomparedCard> uncompared(Long userCardId) {
        return uncomparedCards.stream().filter(u -> u.card().userCardId().equals(userCardId)).findFirst();
    }

    /**
     * 카드가 결과의 어디에 있는지. 추천 뒤에 등록되거나 상태가 바뀌어 결과에 없는 카드면 비어 있다(승인이 다시 판단한다).
     * 어느 값이든 승인의 결제 가능 재확인을 대신하지 않는다.
     */
    public Optional<CardStatus> cardStatus(Long userCardId) {
        if (candidate(userCardId).isPresent()) {
            return Optional.of(CardStatus.CALCULATED);
        }
        if (excluded(userCardId).isPresent()) {
            return Optional.of(CardStatus.EXCLUDED);
        }
        return uncompared(userCardId).map(u -> CardStatus.NOT_COMPARED);
    }

    /**
     * 후보 카드 한 장. 금액은 모두 원이다. 결제 가능과 혜택이 확인된 카드이고, 순혜택 0원도 확인된 값이다.
     * 결제할 금액(chargedAmount)은 담지 않는다. 결제 쪽이 {@code PaymentTerms.chargedAmount(instantDiscount())}로 계산한다.
     *
     * @param card            결제 쪽이 조회한 카드 표시 정보를 그대로 담는다
     * @param netBenefit      이번 결제 순혜택(원, S15P31E103-283). 추천 엔진이 계산한 값 그대로이며 포인트 환산율에 소수가 있으면
     *                        소수가 남는다(예: 3포인트 × 0.5 = 1.5원). 명세(카드 추천 공통 처리, POL-011)대로 공식 환산 뒤 추가
     *                        반올림 없이 순위·동률·0원을 판정하고, 앱 응답에도 같은 값을 내보낸다. 값은 바꾸지 않고 표기만
     *                        맞춘다: 끝의 0을 지우고 정수면 소수점 없이({@code 1500.000000} → {@code 1500}, {@code 1.50} → {@code 1.5})
     * @param selectionReason 1순위를 고른 기준(!63의 selectionReason). 1순위에만 있고 나머지는 null
     * @param ruleVersion     결제 쪽이 조회한 상품 규칙 버전을 그대로 담는다
     */
    public record Candidate(int rank, CardSummary card, java.math.BigDecimal netBenefit, List<BenefitLine> benefits,
            String reason, SelectionReason selectionReason, RuleVersion ruleVersion,
            List<UnallocatedBenefitGroup> unallocatedBenefitGroups, List<ExpectedLimitUse> expectedLimitUses) {
        public Candidate(int rank, CardSummary card, long netBenefit, List<BenefitLine> benefits,
                String reason, SelectionReason selectionReason, RuleVersion ruleVersion) {
            this(rank, card, java.math.BigDecimal.valueOf(netBenefit), benefits, reason, selectionReason, ruleVersion, List.of(), List.of());
        }
        public Candidate(int rank, CardSummary card, java.math.BigDecimal netBenefit, List<BenefitLine> benefits,
                String reason, SelectionReason selectionReason, RuleVersion ruleVersion) {
            this(rank, card, netBenefit, benefits, reason, selectionReason, ruleVersion, List.of(), List.of());
        }
        public Candidate(int rank, CardSummary card, long netBenefit, List<BenefitLine> benefits,
                String reason, SelectionReason selectionReason, RuleVersion ruleVersion, List<UnallocatedBenefitGroup> groups) {
            this(rank, card, java.math.BigDecimal.valueOf(netBenefit), benefits, reason, selectionReason, ruleVersion, groups, List.of());
        }
        public Candidate(int rank, CardSummary card, java.math.BigDecimal netBenefit, List<BenefitLine> benefits,
                String reason, SelectionReason selectionReason, RuleVersion ruleVersion, List<UnallocatedBenefitGroup> groups) {
            this(rank, card, netBenefit, benefits, reason, selectionReason, ruleVersion, groups, List.of());
        }

        public Candidate {
            Objects.requireNonNull(card, "card");
            netBenefit = plain(Objects.requireNonNull(netBenefit, "netBenefit"));
            benefits = List.copyOf(benefits);
            Objects.requireNonNull(ruleVersion, "ruleVersion");
            unallocatedBenefitGroups = unallocatedBenefitGroups == null ? List.of() : List.copyOf(unallocatedBenefitGroups);
            expectedLimitUses = expectedLimitUses == null ? List.of() : List.copyOf(expectedLimitUses);
            var ids = new HashSet<Long>();
            if (benefits.stream().map(BenefitLine::benefitId).distinct().count() != benefits.size()) throw new IllegalArgumentException("혜택 중복");
            for (var group : unallocatedBenefitGroups) for (var id : group.benefitIds()) {
                if (!ids.add(id) || benefits.stream().noneMatch(b -> b.benefitId().equals(id) && b.amount() == null)) {
                    throw new IllegalArgumentException("미확정 묶음의 혜택 참조 오류");
                }
            }
            if (benefits.stream().anyMatch(b -> b.amount() == null && !ids.contains(b.benefitId()))) throw new IllegalArgumentException("NULL 상세 금액 근거 누락");
        }

        /**
         * 값은 그대로 두고 표기만 맞춘다. 환산율이 {@code DECIMAL(19,6)}이라 엔진 값은 {@code 3.000000}처럼 나올 수 있는데,
         * 그대로 JSON에 쓰면 정수를 기대하는 클라이언트가 읽지 못한다. {@code stripTrailingZeros()}만 하면 {@code 1500}이
         * {@code 1.5E+3}이 되므로 정수는 소수 자리 0으로 되돌린다.
         */
        private static java.math.BigDecimal plain(java.math.BigDecimal value) {
            java.math.BigDecimal stripped = value.stripTrailingZeros();
            return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
        }

        /** 승인 때 바로 반영되는 즉시할인({@link BenefitLine#AT_APPROVAL}) 합계. 청구할인·캐시백·적립은 넣지 않는다. */
        public long instantDiscount() {
            if (benefits.stream().anyMatch(b -> "DISCOUNT".equals(b.type()) && b.realization() == null)) {
                throw new IllegalStateException("할인 반영 시점 미확인");
            }
            long total = benefits.stream().filter(BenefitLine::isInstant).filter(b -> b.amount() != null).mapToLong(BenefitLine::amount).reduce(0, Math::addExact);
            for (var group : unallocatedBenefitGroups) {
                var members = benefits.stream().filter(b -> group.benefitIds().contains(b.benefitId())).toList();
                if (members.stream().anyMatch(BenefitLine::isInstant)) {
                    if (!members.stream().allMatch(BenefitLine::isInstant) || !"KRW".equals(group.unit())) throw new IllegalStateException("즉시할인 배분 미확인");
                    total = Math.addExact(total, group.totalAmount());
                }
            }
            return total;
        }
    }

    /** 추천의 예상치이며 승인 확정 차감 기록이 아니다. 미확정 개별 한도는 목록에 공급하지 않는다. */
    public record ExpectedLimitUse(long limitGroupId, int policyVersion, String unit, String period,
                                   java.math.BigDecimal remainingBefore, java.math.BigDecimal consumed) { }

    public record UnallocatedBenefitGroup(List<Long> benefitIds, long limitGroupId, long totalAmount, String unit, String reasonCode) {
        public UnallocatedBenefitGroup {
            benefitIds = List.copyOf(benefitIds);
            if (benefitIds.size() < 2 || benefitIds.stream().distinct().count() != benefitIds.size()
                    || benefitIds.stream().anyMatch(id -> id <= 0) || limitGroupId <= 0 || totalAmount < 0
                    || !("KRW".equals(unit) || "POINT".equals(unit)) || !"SHARED_LIMIT_ALLOCATION_UNRESOLVED".equals(reasonCode)) {
                throw new IllegalArgumentException("미확정 배분 묶음 오류");
            }
        }
    }

    /**
     * 화면에 보일 카드 정보. 결제 쪽이 보유 카드를 조회한 값({@code PaymentCardLookup})을 그대로 담고, 추천 엔진이 카드를 다시 조회하지 않는다.
     *
     * @param alias       사용자가 정한 별칭. 없으면 null
     * @param last4       카드번호 끝 4자리
     * @param issuerCode  카드사 코드
     * @param productName 상품명
     */
    public record CardSummary(Long userCardId, Long cardProductId, CardType cardType, boolean defaultCard,
            String alias, String last4, String issuerCode, String productName) {}

    /**
     * 적용될 혜택 한 줄.
     *
     * @param rawAmount   비율로 계산한 명목 금액
     * @param amount      한도를 적용한 실제 평가액
     * @param realization AT_APPROVAL(즉시) / AT_BILLING(청구) / AFTER_BILLING(캐시백) / POINT_ACCRUAL(적립)
     */
    public record BenefitLine(Long benefitId, String name, String type, java.math.BigDecimal rawAmount, Long amount,
            String realization) {
        public BenefitLine(Long benefitId, String name, String type, long rawAmount, long amount, String realization) {
            this(benefitId, name, type, java.math.BigDecimal.valueOf(rawAmount), Long.valueOf(amount), realization);
        }

        public BenefitLine {
            Objects.requireNonNull(benefitId);
            if (amount != null && amount < 0) throw new IllegalArgumentException("음수 혜택 금액");
        }

        /** 승인 때 바로 반영되는 즉시할인의 realization 값 */
        public static final String AT_APPROVAL = "AT_APPROVAL";

        public boolean isInstant() {
            return AT_APPROVAL.equals(realization);
        }
    }

    public record ExcludedCard(CardSummary card, ExclusionReason reason, String message) {}

    /**
     * 비교하지 못한 카드(!63의 NOT_COMPARED).
     *
     * @param message 앱에 보일 문구. null이면 사유의 기본 문구
     */
    public record UncomparedCard(CardSummary card, UncomparedReason reason, String message) {

        public UncomparedCard {
            Objects.requireNonNull(card, "card");
            Objects.requireNonNull(reason, "reason");
            message = message == null ? reason.message() : message;
        }

        /**
         * 사용자가 직접 골라 혜택 없이 원금으로 결제하는 경로를 쓸 수 있는지. 결제 가능이 확인되고 혜택만 미확인일 때만 참이다.
         * 참이어도 승인은 결제 가능 조건을 다시 확인하고, 사용자가 {@link #NO_BENEFIT_NOTICE}를 확인한 뒤에만 승인한다.
         */
        public boolean directPaymentAllowed() {
            return reason == UncomparedReason.BENEFIT_DATA_INSUFFICIENT;
        }
    }

    /** 혜택 계산에 쓴 상품 규칙 버전. 결제 쪽이 조회한 버전을 재사용하고, 승인 때 같은 버전으로 다시 계산한다. */
    public record RuleVersion(Long cardProductId, Integer version, java.util.Map<Long, Integer> benefitVersions) {
        public RuleVersion(Long cardProductId, int version) { this(cardProductId, version, java.util.Map.of()); }
        public RuleVersion { benefitVersions = benefitVersions == null ? java.util.Map.of() : java.util.Map.copyOf(benefitVersions); }
    }

    /** 추천 상태. 이름과 판정은 BE3 엔진(!63)의 {@code Status}와 같다. */
    public enum Status {
        /** 모든 비교 대상이 확인된 추천 */
        READY,
        /** 확인된 후보 중 추천. 비교하지 못한 카드가 있다 */
        PARTIAL_READY,
        /** 확인된 후보가 없고 비교하지 못한 카드가 있다. 1순위가 없다 */
        DATA_INSUFFICIENT,
        /** 확인된 사유로 결제할 수 있는 카드가 없다(제외 카드만 있거나 카드가 없음) */
        NO_ELIGIBLE_CARD
    }

    /** 카드가 결과의 어느 목록에 있는지. 이름은 BE3 엔진(!63)의 {@code CardStatus}와 같다. */
    public enum CardStatus {
        CALCULATED,
        EXCLUDED,
        NOT_COMPARED
    }

    /**
     * 결제 불가 사유. 카드 상태·할부 사유는 BE3 엔진(!63)의 제외 코드와, 결제 가능 사유는 승인 거절의 failure.code와 이름이 같다.
     * 혜택 자료가 없는 것은 결제 불가가 아니다. 그런 카드는 {@link UncomparedReason#BENEFIT_DATA_INSUFFICIENT}로 미비교 목록에 둔다.
     */
    public enum ExclusionReason {
        /** YAMU 등록 상태가 ACTIVE가 아님 */
        CARD_NOT_ACTIVE,
        CARD_SUSPENDED,
        CARD_EXPIRED,
        /** 할부 결제에 체크카드. 체크카드는 일시불만이다(POL-013) */
        CHECK_CARD_INSTALLMENT_NOT_SUPPORTED,
        UNPAID_BILL,
        CREDIT_LIMIT_EXCEEDED,
        INSUFFICIENT_BALANCE,
        PAYMENT_ACCOUNT_DISCONNECTED
    }

    /** 비교하지 못한 사유. 혜택만 미확인인지, 결제 가능 여부가 미확인인지 나눈다(2026-11-02 정책 변경). 문구는 !63과 같다. */
    public enum UncomparedReason {
        /** 결제 가능은 확인했고 혜택 정보만 확인하지 못함(!63과 같은 코드). 직접 고르면 혜택 없이 원금으로 결제할 수 있다 */
        BENEFIT_DATA_INSUFFICIENT("혜택 정보를 확인하지 못해 비교에서 제외했어요."),
        /** 체크카드 잔액·신용 가용 한도·미납 등 결제 가능 여부를 확인하지 못함. 혜택도 계산하지 않았다. 확인 전에는 승인하지 않는다 */
        PAYMENT_AVAILABILITY_UNKNOWN("결제 가능 여부를 확인하지 못해 비교에서 제외했어요.");

        private final String message;

        UncomparedReason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** 1순위가 없는 이유(FR-018, NFR-006). {@link Status}의 두 값과 같다. 기존 호출부 호환용이다. */
    public enum UnavailableReason {
        /** 확인된 사유로 결제할 수 있는 카드가 없음 */
        NO_ELIGIBLE_CARD,
        /**
         * 확인된 후보가 없고 비교하지 못한 카드가 있음. 자동 선택·확정 순위를 내지 않는다.
         * 혜택만 미확인인 카드는 사용자가 직접 골라 혜택 없이 결제할 수 있다
         */
        DATA_INSUFFICIENT
    }

    /**
     * 1순위를 고른 기준(카드 추천 정책 POL-011). 이름은 BE3 엔진(!63)의 {@code SelectionReason}과 같다.
     * 순혜택 최대가 한 장이면 MAXIMUM_NET_BENEFIT, 정확히 같은 최대가 여럿이면 기본 카드 → 최소 혜택 실적 기준 신규 충족 → 고정 정렬(카드 번호 오름차순).
     */
    public enum SelectionReason {
        /** 순혜택이 가장 큰 카드가 한 장 */
        MAXIMUM_NET_BENEFIT,
        /** 동률 중 설정한 기본 카드 */
        DEFAULT_CARD,
        /** 동률 중 이번 결제로 최소 혜택 실적 기준을 새로 충족(결제 전 누적액 < 기준 ≤ 누적액 + 이번 인정액) */
        NEW_MINIMUM_PERFORMANCE,
        /** 동률 중 고정 정렬(보유 카드 번호 오름차순) */
        FIXED_ORDER
    }
}
