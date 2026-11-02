package com.yamu.be.recommendation;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.recommendation.CardBenefitCalculator.Calculation;
import com.yamu.be.recommendation.RecommendationInput.Availability;
import com.yamu.be.recommendation.RecommendationInput.CardCandidate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** POL-011. 호출 간 공유 상태가 없고 DB·승인·조회·저장에 의존하지 않는 내부 계산 진입점. */
public final class RecommendationSelector {
    private final CardBenefitCalculator calculator = new CardBenefitCalculator();
    // UserCardRepository.findOrdered와 동일. MySQL ASC의 NULL 등록 시각은 앞에 온다.
    private static final Comparator<CardCandidate> FIXED_ORDER = Comparator
            .comparing(CardCandidate::displayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CardCandidate::registeredAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(CardCandidate::userCardId);

    public enum Status { READY, PARTIAL_READY, NO_ELIGIBLE_CARD, DATA_INSUFFICIENT }
    public enum CardStatus { CALCULATED, EXCLUDED, NOT_COMPARED }
    public enum SelectionReason { MAXIMUM_NET_BENEFIT, DEFAULT_CARD, NEW_MINIMUM_PERFORMANCE, FIXED_ORDER }

    public record CardEvaluation(long userCardId, CardStatus status, String reason, Calculation calculation) {
        /** 카드 표시명·문의 목적지는 기존 앱 계약으로 연결한다. 여기서는 URL을 만들지 않는다. */
        public String message() {
            if (status != CardStatus.NOT_COMPARED) {
                return null;
            }
            return calculation == null
                    ? "결제 가능 여부를 확인하지 못해 비교에서 제외했어요. 관리자에게 문의해주세요."
                    : "혜택 정보를 확인하지 못해 비교에서 제외했어요. 해당 카드의 혜택 정보는 관리자에게 문의해주세요.";
        }
    }

    /** rankedCards는 순위 순서의 전체 후보. 보류 결과는 선택 카드·확정 순위를 제공하지 않는다. */
    public record Result(long paymentId, Status status, Long selectedUserCardId, SelectionReason selectionReason,
                         String message, boolean performanceTieBreakSkipped, boolean noBenefit,
                         List<CardEvaluation> rankedCards, List<CardEvaluation> evaluatedCards) {
        public Result {
            rankedCards = List.copyOf(rankedCards);
            evaluatedCards = List.copyOf(evaluatedCards);
        }

        /** 확인된 결제 불가(EXCLUDED)와 별개인 미비교 카드. 계산 문제의 상세 코드는 calculation.problems에 있다. */
        public List<CardEvaluation> uncomparedCards() {
            return evaluatedCards.stream().filter(card -> card.status() == CardStatus.NOT_COMPARED).toList();
        }
    }

    public Result select(RecommendationInput input) {
        // TODO(S15P31E103-221): 실제 정책·실적·사용량·잔액 공급 연결은 아직 없다.
        // 공급/서비스 계층이 복구 가능한 읽기만 제한적으로 재조회한 뒤 미확인 카드와 사유를 입력으로 전달해야 한다.
        // 정책 미등록은 반복 조회·추정하지 않으며, 이 엔진에는 네트워크 호출·재시도를 넣지 않는다.
        List<CardEvaluation> evaluations = input.cards().stream()
                .sorted(Comparator.comparingLong(CardCandidate::userCardId))
                .map(card -> evaluate(input, card)).toList();
        boolean partial = evaluations.stream().anyMatch(card -> card.status() == CardStatus.NOT_COMPARED);
        List<CardEvaluation> calculated = evaluations.stream()
                .filter(card -> card.status() == CardStatus.CALCULATED).toList();
        if (calculated.isEmpty()) {
            return new Result(input.paymentId(), partial ? Status.DATA_INSUFFICIENT : Status.NO_ELIGIBLE_CARD,
                    null, null, partial ? "추천에 필요한 정보를 확인하지 못했습니다."
                    : "이 거래에 결제 가능한 카드가 없습니다.", false, false, List.of(), evaluations);
        }

        BigDecimal maximum = calculated.stream().map(card -> card.calculation().netBenefit())
                .max(BigDecimal::compareTo).orElseThrow();
        Map<Long, CardCandidate> cards = input.cards().stream()
                .collect(Collectors.toMap(CardCandidate::userCardId, Function.identity()));
        List<CardEvaluation> tied = calculated.stream()
                .filter(card -> card.calculation().netBenefit().compareTo(maximum) == 0)
                .sorted(Comparator.comparing(card -> cards.get(card.userCardId()), FIXED_ORDER)).toList();
        CardEvaluation selected;
        SelectionReason reason;
        boolean skipped = false;
        boolean multipleNewlyMet = false;
        if (tied.size() == 1) {
            selected = tied.get(0);
            reason = SelectionReason.MAXIMUM_NET_BENEFIT;
        } else {
            selected = tied.stream().filter(card -> Long.valueOf(card.userCardId()).equals(input.defaultUserCardId()))
                    .findFirst().orElse(null);
            if (selected != null) {
                reason = SelectionReason.DEFAULT_CARD;
            } else {
                skipped = tied.stream().anyMatch(card -> cards.get(card.userCardId()).performance() == null
                        || !cards.get(card.userCardId()).performance().isKnown());
                List<CardEvaluation> newlyMet = skipped ? List.of() : tied.stream()
                        .filter(card -> cards.get(card.userCardId()).performance().newlyMeetsMinimum()).toList();
                if (newlyMet.size() == 1) {
                    selected = newlyMet.get(0);
                    reason = SelectionReason.NEW_MINIMUM_PERFORMANCE;
                } else {
                    // 여러 신규 충족 카드가 있으면 그 카드들에서만 고정 정렬한다.
                    multipleNewlyMet = newlyMet.size() > 1;
                    selected = (newlyMet.isEmpty() ? tied : newlyMet).get(0);
                    reason = SelectionReason.FIXED_ORDER;
                }
            }
        }

        // POL-011의 보조 기준은 최대 동률의 최종 선택에만 적용한다.
        // 나머지는 순혜택 내림차순·보유 카드 ID 오름차순으로 안정적으로 제공한다.
        List<CardEvaluation> ranked = new ArrayList<>();
        ranked.add(selected);
        long selectedId = selected.userCardId();
        calculated.stream().filter(card -> card.userCardId() != selectedId)
                .sorted(Comparator.<CardEvaluation, BigDecimal>comparing(card -> card.calculation().netBenefit())
                        .reversed().thenComparingLong(CardEvaluation::userCardId))
                .forEach(ranked::add);
        boolean allZero = calculated.stream().allMatch(card -> card.calculation().netBenefit().signum() == 0);
        return new Result(input.paymentId(), partial ? Status.PARTIAL_READY : Status.READY,
                selected.userCardId(), reason, message(reason, allZero, skipped, multipleNewlyMet, partial, maximum),
                skipped, allZero, ranked, evaluations);
    }

    private CardEvaluation evaluate(RecommendationInput input, CardCandidate card) {
        String exclusion = exclusion(input, card);
        if (exclusion != null) {
            return new CardEvaluation(card.userCardId(), CardStatus.EXCLUDED, exclusion, null);
        }
        if (card.availability() == Availability.UNKNOWN) {
            return new CardEvaluation(card.userCardId(), CardStatus.NOT_COMPARED,
                    card.availabilityReason(), null);
        }
        Calculation calculation = calculator.calculate(input.transaction(), card.policy());
        String problem = calculation.problems().stream().anyMatch(item -> item.code().equals("BASE_AMOUNT_INVALID"))
                ? "BASE_AMOUNT_INVALID" : "BENEFIT_DATA_INSUFFICIENT";
        return new CardEvaluation(card.userCardId(), calculation.complete() ? CardStatus.CALCULATED
                : CardStatus.NOT_COMPARED, calculation.complete() ? null : problem, calculation);
    }

    private String exclusion(RecommendationInput input, CardCandidate card) {
        if (card.registrationStatus() != CardRegistrationStatus.ACTIVE) {
            return "CARD_NOT_ACTIVE";
        }
        if (card.issuerStatus() != CardIssuerStatus.NORMAL) {
            return card.issuerStatus() == CardIssuerStatus.EXPIRED ? "CARD_EXPIRED" : "CARD_SUSPENDED";
        }
        if (input.transaction().plan() == RecommendationInput.Plan.INSTALLMENT
                && card.cardType() == RecommendationInput.CardType.CHECK) {
            return "CHECK_CARD_INSTALLMENT_NOT_SUPPORTED";
        }
        return card.availability() == Availability.UNAVAILABLE ? card.availabilityReason() : null;
    }

    private String message(SelectionReason reason, boolean allZero, boolean skipped, boolean multipleNewlyMet,
                           boolean partial, BigDecimal maximum) {
        String choice = switch (reason) {
            case MAXIMUM_NET_BENEFIT -> "이번 결제의 순혜택이 가장 큰 카드를 선택했습니다.";
            case DEFAULT_CARD -> "현재 혜택이 같아 설정한 기본 카드를 선택했습니다.";
            case NEW_MINIMUM_PERFORMANCE -> "현재 혜택이 같아 이번 거래로 최소 혜택 실적 기준을 새로 충족하는 카드를 선택했습니다.";
            case FIXED_ORDER -> multipleNewlyMet
                    ? "현재 혜택이 같아 최소 혜택 실적 기준을 새로 충족하는 카드들 중 고정 정렬 순서로 선택했습니다."
                    : "현재 혜택이 같아 고정 정렬 순서로 카드를 선택했습니다.";
        };
        return (partial ? "확인된 카드 중 추천합니다. " : "")
                + "예상 순혜택은 " + maximum.stripTrailingZeros().toPlainString() + "원입니다. "
                + (allZero ? "확인된 후보의 이번 결제 순혜택은 모두 0원입니다. " : "") + choice
                + (skipped ? " 실적 보조 정보가 미확인되어 동률 후보 전체에서 실적 기준을 생략했습니다." : "");
    }
}
