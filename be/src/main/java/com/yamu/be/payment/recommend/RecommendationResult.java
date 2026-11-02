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
 */
public record RecommendationResult(String recommendationId, LocalDateTime calculatedAt, boolean estimate,
        List<Candidate> candidates, List<ExcludedCard> excludedCards, List<UncomparedCard> uncomparedCards) {

    /** 혜택만 미확인인 카드를 사용자가 직접 골랐을 때 승인 전에 보여 주고 확인받는 문구(2026-11-02 정책 변경). */
    public static final String NO_BENEFIT_NOTICE = "혜택 정보를 확인할 수 없어 혜택 없이 결제됩니다.";

    public RecommendationResult {
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
        return uncomparedCards.isEmpty() ? Status.READY : Status.PARTIAL_READY;
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
        return !candidates.isEmpty() && candidates.stream().allMatch(c -> c.netBenefit() == 0);
    }

    public Optional<Candidate> topCandidate() {
        return candidates.stream().findFirst();
    }

    public Optional<Candidate> candidate(Long userCardId) {
        return candidates.stream().filter(c -> c.card().userCardId().equals(userCardId)).findFirst();
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

    /** 후보 카드 한 장. 금액은 모두 원이다. 결제 가능과 혜택이 확인된 카드이고, 순혜택 0원도 확인된 값이다. */
    public record Candidate(int rank, CardSummary card, long netBenefit, List<BenefitLine> benefits,
            long chargedAmount, String reason, TieBreak tieBreak, RuleVersion ruleVersion) {

        public Candidate {
            Objects.requireNonNull(card, "card");
            benefits = List.copyOf(benefits);
            Objects.requireNonNull(ruleVersion, "ruleVersion");
        }
    }

    /**
     * 화면에 보일 카드 정보. 추천이 카드 저장소를 다시 읽지 않도록 결과에 같이 담는다.
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
    public record BenefitLine(Long benefitId, String name, String type, long rawAmount, long amount,
            String realization) {}

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

    /** 혜택 계산에 쓴 상품 규칙 버전. 승인 때 같은 버전으로 다시 계산한다. */
    public record RuleVersion(Long cardProductId, int version) {}

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
     * 결제 불가 사유. 승인 거절의 failure.code와 이름을 맞췄다(API 명세 「결제 승인 요청」).
     * 혜택 자료가 없는 것은 결제 불가가 아니다. 그런 카드는 {@link UncomparedReason#BENEFIT_DATA_INSUFFICIENT}로 미비교 목록에 둔다.
     */
    public enum ExclusionReason {
        CARD_SUSPENDED,
        CARD_EXPIRED,
        UNPAID_BILL,
        CREDIT_LIMIT_EXCEEDED,
        INSUFFICIENT_BALANCE,
        /** 할부 결제에 체크카드. 체크카드는 일시불만이다(POL-013) */
        INSTALLMENT_NOT_SUPPORTED
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
     * 최대 순혜택이 정확히 같을 때 순위를 정한 규칙(카드 추천 정책 POL-011). 동률이 아니었으면 null.
     * 혜택 실현 편의성은 동률 기준에서 뺐다(2026-10-31).
     */
    public enum TieBreak {
        /** 설정한 기본 카드 */
        DEFAULT_CARD,
        /** 이번 결제로 최소 혜택 실적 기준을 새로 충족(결제 전 인정 실적 < 기준 ≤ 결제 전 인정 실적 + 이번 거래 인정 실적) */
        MEETS_THRESHOLD,
        /** 고정 정렬 순서 */
        FIXED_ORDER
    }
}
