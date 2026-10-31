package com.yamu.be.payment.recommend;

import com.yamu.be.card.domain.CardType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 추천 결과. 명세의 내부 결과 예시를 따른다. 앱 응답(바코드 조회·결제 상세)은 이 값에서 필요한 부분만 꺼내 만든다.
 *
 * @param recommendationId  추천 식별자(예: rec_7c1e5a1b2c3d). 결제 진행 중 Redis에 둘 스냅샷의 이름
 * @param calculatedAt      계산 시각(UTC)
 * @param estimate          잔액 조회 실패 등으로 캐시 값으로 계산했으면 true
 * @param candidates        순혜택 내림차순 후보. 1순위가 첫 번째
 * @param excludedCards     결제할 수 없어 뺀 카드와 사유
 * @param unavailableReason 후보가 하나도 없을 때의 이유. 후보가 있으면 null
 */
public record RecommendationResult(String recommendationId, LocalDateTime calculatedAt, boolean estimate,
        List<Candidate> candidates, List<ExcludedCard> excludedCards, UnavailableReason unavailableReason) {

    public RecommendationResult {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        candidates = List.copyOf(candidates);
        excludedCards = List.copyOf(excludedCards);
        if (candidates.isEmpty() == (unavailableReason == null)) {
            throw new IllegalArgumentException("후보가 없으면 unavailableReason이 있어야 하고, 있으면 없어야 한다.");
        }
    }

    public boolean isAvailable() {
        return unavailableReason == null;
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

    /** 후보 카드 한 장. 금액은 모두 원이다. */
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

    /** 혜택 계산에 쓴 상품 규칙 버전. 승인 때 같은 버전으로 다시 계산한다. */
    public record RuleVersion(Long cardProductId, int version) {}

    /**
     * 결제 불가 사유. 승인 거절의 failure.code와 이름을 맞췄다(API 명세 「결제 승인 요청」).
     * 혜택 자료가 없는 것은 결제 불가가 아니다. 그런 후보가 있으면 순위를 내지 않고 {@link UnavailableReason#DATA_INSUFFICIENT}다.
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

    /** 후보가 하나도 없는 이유(FR-018, NFR-006). */
    public enum UnavailableReason {
        /** 결제할 수 있는 카드가 없음 */
        NO_ELIGIBLE_CARD,
        /** 결제 가능한 후보의 순혜택·한도를 확정할 수 없음. 확정 선택·순위를 내지 않는다 */
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
