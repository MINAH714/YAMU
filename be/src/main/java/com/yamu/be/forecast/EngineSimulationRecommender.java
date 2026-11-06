package com.yamu.be.forecast;

import com.yamu.be.recommendation.BasicPurchaseSimulation;
import com.yamu.be.recommendation.CalculationOptions;
import com.yamu.be.recommendation.CardBenefitCalculator.BenefitValue;
import com.yamu.be.recommendation.CardBenefitCalculator.Calculation;
import com.yamu.be.recommendation.CardBenefitCalculator.UnallocatedBenefitGroup;
import com.yamu.be.recommendation.RecommendationDataIssue;
import com.yamu.be.recommendation.RecommendationInput;
import com.yamu.be.recommendation.RecommendationSelector;
import com.yamu.be.recommendation.RecommendationSelector.CardEvaluation;
import com.yamu.be.recommendation.RecommendationSelector.CardStatus;
import com.yamu.be.recommendation.RecommendationService;
import com.yamu.be.recommendation.SimulationRule;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 가상 구매의 추천·혜택을 추천 엔진({@link RecommendationService#recommendSimulation})으로 계산한다(Task 7.2.2, 2026-11-06 결정).
 *
 * <ul>
 *   <li>거래: 구매일 낮 12시(KST)에 결제한다고 가정하고, {@link BasicPurchaseSimulation}으로 일반 구매·일반 매장을 가정한다.
 *       브랜드를 고르지 않았으면 브랜드에 달린 혜택을 빼고 계산한다.</li>
 *   <li>혜택: 계산을 끝낸 카드만 순혜택(소수 그대로)·항목(결제 추천과 같은 모양)을 준다. 아니면 null이고 이유는 missingInformation이다.</li>
 *   <li>즉시할인 합계는 결제 추천과 같은 규칙이다: 적용 시점이 승인 때(AT_APPROVAL)인 혜택의 금액과, 배분이 안 정해진 즉시할인 묶음의
 *       원화 합계. 할인 혜택의 적용 시점을 모르면 즉시할인을 정할 수 없어 혜택을 null로 둔다.</li>
 *   <li>대안: 계산한 다른 카드(순혜택 순)와 비교하지 못한 카드(순혜택 null, 이유). 엔진이 제외한 카드는 넣지 않는다.</li>
 *   <li>엔진은 오늘 잔액으로 체크카드를 거른다. 사용자가 고른 카드가 그래서 제외됐으면 혜택만 비우고 잔고 비교는 그대로 한다.</li>
 * </ul>
 *
 * <p>엔진이 금융망 잔액을 읽으므로 DB 트랜잭션 밖에서 불러야 한다(엔진이 트랜잭션 안이면 거절한다).
 */
@Slf4j
@Component
class EngineSimulationRecommender implements SimulationRecommender {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    static final Assumption PURCHASE_TIME = new Assumption("YAMU_PURCHASE_TIME_NOON",
            "구매 시각을 구매일 낮 12시로 가정했어요(요일·시간대 조건 판정).");
    static final Assumption BRAND_NOT_SELECTED = new Assumption("YAMU_BRAND_NOT_SELECTED",
            "브랜드 혜택을 제외한 예상 금액이에요. 브랜드를 고르면 더 커질 수 있어요.");
    private static final String AT_APPROVAL = "AT_APPROVAL";
    /** 신용한도 확인 생략은 응답의 creditLimit으로 따로 보이므로 빠진 정보로 다시 싣지 않는다. */
    private static final Set<String> SHOWN_ELSEWHERE = Set.of("CREDIT_LIMIT_CHECK_SKIPPED");
    private static final String UNCOMPARED_NOTE = "혜택 정보가 부족해 비교하지 못했어요.";

    private final RecommendationService engine;

    EngineSimulationRecommender(RecommendationService engine) {
        this.engine = engine;
    }

    @Override
    public Result recommend(Request request) {
        RecommendationService.Result result;
        try {
            result = engine.recommendSimulation(request.userId(), transaction(request),
                    new CalculationOptions(request.merchantId() == null));
        } catch (RecommendationService.RecommendationReadException e) {
            log.warn("가상 구매 추천 엔진 읽기 실패 userId={} code={}", request.userId(), e.getClass().getSimpleName());
            if (request.userCardId() == null) {
                return Result.unavailable("RECOMMENDATION_UNAVAILABLE");
            }
            return Result.of(new Recommendation(request.userCardId(), null, null, null, List.of(PURCHASE_TIME),
                    List.of(new MissingInformation("RECOMMENDATION_READ_FAILED", null))));
        }
        RecommendationSelector.Result selection = result.recommendation();
        Long target = request.userCardId() != null ? request.userCardId() : selection.selectedUserCardId();
        if (target == null) {
            return Result.unavailable(selection.status() == RecommendationSelector.Status.NO_ELIGIBLE_CARD
                    ? "NO_ELIGIBLE_CARD" : "BENEFIT_DATA_INSUFFICIENT");
        }
        Optional<CardEvaluation> evaluation = selection.evaluatedCards().stream()
                .filter(card -> card.userCardId() == target).findFirst();
        RecommendationService.CardContext context = result.cards().get(target);

        List<MissingInformation> missing = new ArrayList<>();
        Benefit benefit = null;
        Calculation calculation = evaluation.map(CardEvaluation::calculation).orElse(null);
        if (evaluation.isEmpty()) {
            missing.add(new MissingInformation("CARD_NOT_EVALUATED", null));
        } else if (evaluation.get().status() == CardStatus.CALCULATED && calculation != null && calculation.complete()) {
            benefit = benefit(calculation, context).orElse(null);
            if (benefit == null) {
                missing.add(new MissingInformation("INSTANT_DISCOUNT_TIMING_UNKNOWN", null));
            }
        } else {
            if (evaluation.get().status() != CardStatus.CALCULATED && evaluation.get().reason() != null) {
                missing.add(new MissingInformation(evaluation.get().reason(), null));
            }
            if (calculation != null) {
                calculation.missingInformation().forEach(info -> missing.add(new MissingInformation(info.code(), info.field())));
                calculation.problems().forEach(problem -> missing.add(new MissingInformation(problem.code(), null)));
            }
        }
        result.issues().stream()
                .filter(issue -> issue.userCardId() == null || issue.userCardId().equals(target))
                .map(RecommendationDataIssue::code)
                .filter(code -> !SHOWN_ELSEWHERE.contains(code))
                .forEach(code -> missing.add(new MissingInformation(code, null)));

        return Result.of(new Recommendation(target, benefit, alternatives(request, selection, result.cards(), target),
                null, assumptions(result.simulationRules(), target, benefit != null
                        && calculation != null && !calculation.excludedBenefits().isEmpty()),
                distinct(missing)));
    }

    /** 구매일 낮 12시(KST), 요청한 결제 방식·개월 수, 확인된 가맹점·업종. 물품 코드는 넘기지 않는다(화면 표시용, 2026-11-05 결정). */
    private static RecommendationInput.Transaction transaction(Request request) {
        RecommendationInput.Transaction plain = new RecommendationInput.Transaction(request.terms().amount(),
                RecommendationInput.Plan.valueOf(request.terms().plan().name()), request.terms().installmentMonthsOrOne(),
                request.purchaseDate().atTime(LocalTime.NOON).atZone(KOREA).toInstant(), request.merchantId(),
                request.merchantCategoryCode(), Map.of(), null);
        return BasicPurchaseSimulation.apply(plain, request.paymentChannel(), null);
    }

    /** 결제 추천과 같은 혜택 줄과 즉시할인 합계. 할인 혜택의 적용 시점을 모르면 empty. */
    private static Optional<Benefit> benefit(Calculation calculation, RecommendationService.CardContext context) {
        List<BenefitItem> items = new ArrayList<>();
        long instant = 0;
        for (BenefitValue value : calculation.benefits()) {
            String realization = context == null ? null : context.benefitTiming().get(value.benefitId());
            if ("DISCOUNT".equals(value.type().name()) && realization == null) {
                return Optional.empty();
            }
            items.add(new BenefitItem(value.benefitId(), value.name(), value.type().name(), realization,
                    value.krwValue() == null ? null : plain(value.krwValue())));
            if (AT_APPROVAL.equals(realization) && value.value() != null) {
                instant = Math.addExact(instant, value.value().longValueExact());
            }
        }
        for (UnallocatedBenefitGroup group : calculation.unallocatedBenefitGroups()) {
            List<BenefitItem> members = items.stream().filter(item -> group.benefitIds().contains(item.benefitId())).toList();
            if (members.stream().anyMatch(item -> AT_APPROVAL.equals(item.realization()))) {
                if (!members.stream().allMatch(item -> AT_APPROVAL.equals(item.realization()))
                        || !"KRW".equals(group.unit().name())) {
                    return Optional.empty();
                }
                instant = Math.addExact(instant, group.totalAmount().longValueExact());
            }
        }
        return Optional.of(new Benefit(plain(calculation.netBenefit()), List.copyOf(items), instant));
    }

    /** 계산한 다른 카드(순혜택 순)와 비교하지 못한 카드. 엔진이 제외한 카드는 넣지 않는다. */
    private static List<Alternative> alternatives(Request request, RecommendationSelector.Result selection,
            Map<Long, RecommendationService.CardContext> cards, long target) {
        String plan = request.terms().plan().name();
        Integer months = request.terms().installmentMonths();
        List<Alternative> alternatives = new ArrayList<>();
        for (CardEvaluation card : selection.rankedCards()) {
            if (card.userCardId() != target) {
                alternatives.add(new Alternative(card.userCardId(), nickname(cards.get(card.userCardId())),
                        cardType(cards.get(card.userCardId())), plan, months,
                        plain(card.calculation().netBenefit()), null));
            }
        }
        for (CardEvaluation card : selection.evaluatedCards()) {
            if (card.userCardId() != target && card.status() == CardStatus.NOT_COMPARED) {
                String note = card.message() != null ? card.message() : UNCOMPARED_NOTE;
                alternatives.add(new Alternative(card.userCardId(), nickname(cards.get(card.userCardId())),
                        cardType(cards.get(card.userCardId())), plan, months, null, note));
            }
        }
        return List.copyOf(alternatives);
    }

    /** 구매 시각 가정, 엔진이 그 카드 계산에 쓴 가정, 브랜드 혜택을 빼고 계산을 끝냈을 때의 브랜드 안내. */
    private static List<Assumption> assumptions(List<SimulationRule> rules, long target, boolean brandExcluded) {
        Map<String, Assumption> byId = new LinkedHashMap<>();
        byId.put(PURCHASE_TIME.id(), PURCHASE_TIME);
        for (SimulationRule rule : rules) {
            if (rule.userCardId() == null || rule.userCardId() == target) {
                byId.putIfAbsent(rule.id(), new Assumption(rule.id(), rule.description()));
            }
        }
        if (brandExcluded) {
            byId.put(BRAND_NOT_SELECTED.id(), BRAND_NOT_SELECTED);
        }
        return List.copyOf(byId.values());
    }

    private static List<MissingInformation> distinct(List<MissingInformation> missing) {
        return missing.stream().distinct().collect(Collectors.toUnmodifiableList());
    }

    private static String nickname(RecommendationService.CardContext context) {
        if (context == null) {
            return null;
        }
        return context.alias() != null && !context.alias().isBlank() ? context.alias() : context.productName();
    }

    private static com.yamu.be.card.domain.CardType cardType(RecommendationService.CardContext context) {
        return context == null ? null : context.cardType();
    }

    /** 결제 추천과 같은 표기: 값은 그대로, 3.000000은 3, 1500은 1E+3이 아니라 1500. */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
