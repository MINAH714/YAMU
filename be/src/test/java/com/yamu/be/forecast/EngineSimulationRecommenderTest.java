package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.PosPurchaseFacts;
import com.yamu.be.recommendation.BenefitPolicy.BenefitType;
import com.yamu.be.recommendation.BenefitPolicy.Unit;
import com.yamu.be.recommendation.CalculationOptions;
import com.yamu.be.recommendation.CardBenefitCalculator.BenefitValue;
import com.yamu.be.recommendation.CardBenefitCalculator.Calculation;
import com.yamu.be.recommendation.CardBenefitCalculator.ExcludedBenefit;
import com.yamu.be.recommendation.CardBenefitCalculator.MissingInformation;
import com.yamu.be.recommendation.CardBenefitCalculator.Problem;
import com.yamu.be.recommendation.CardBenefitCalculator.UnallocatedBenefitGroup;
import com.yamu.be.recommendation.RecommendationDataIssue;
import com.yamu.be.recommendation.RecommendationInput;
import com.yamu.be.recommendation.RecommendationSelector;
import com.yamu.be.recommendation.RecommendationSelector.CardEvaluation;
import com.yamu.be.recommendation.RecommendationSelector.CardStatus;
import com.yamu.be.recommendation.RecommendationSelector.Status;
import com.yamu.be.recommendation.RecommendationService;
import com.yamu.be.recommendation.SimulationRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 추천 엔진 결과를 가상 구매 응답 모양으로 바꾸는 규칙(엔진은 가짜, 2026-11-06 결정). */
@ExtendWith(MockitoExtension.class)
class EngineSimulationRecommenderTest {
    private static final long USER = 7L;
    private static final long CARD = 10L;
    private static final long OTHER = 11L;
    private static final long UNCOMPARED = 12L;
    private static final long EXCLUDED = 13L;
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 11, 10);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Mock RecommendationService engine;
    EngineSimulationRecommender recommender;

    @BeforeEach
    void setUp() {
        recommender = new EngineSimulationRecommender(engine);
    }

    @Test
    void 엔진이_고른_카드의_순혜택은_소수_그대로이고_즉시할인은_승인_때_혜택만_더한다() {
        Calculation calculation = new Calculation(true, new BigDecimal("30000.50"), List.of(
                benefit(1, BenefitType.DISCOUNT, "30000", "30000"),
                benefit(2, BenefitType.CASHBACK, "0.5", "0.5")), List.of(), List.of());
        Calculation other = new Calculation(true, new BigDecimal("1000.000"),
                List.of(benefit(3, BenefitType.DISCOUNT, "1000", "1000")), List.of(), List.of());
        stub(selection(Status.PARTIAL_READY, CARD,
                        List.of(calculated(CARD, calculation), calculated(OTHER, other)),
                        List.of(calculated(CARD, calculation), calculated(OTHER, other),
                                new CardEvaluation(UNCOMPARED, CardStatus.NOT_COMPARED, "POLICY_INCOMPLETE",
                                        new Calculation(false, null, List.of(), List.of(),
                                                List.of(new Problem(5L, "POLICY_INCOMPLETE")))),
                                new CardEvaluation(EXCLUDED, CardStatus.EXCLUDED, "INSUFFICIENT_BALANCE", null))),
                List.of(),
                List.of(new SimulationRule("RULE_CARD", 1, "이 카드 가정", CARD, 1L, List.of("x")),
                        new SimulationRule("RULE_COMMON", 1, "공통 가정", null, null, List.of("y")),
                        new SimulationRule("RULE_OTHER", 1, "다른 카드 가정", OTHER, 3L, List.of("z"))),
                Map.of(1L, "AT_APPROVAL", 2L, "AT_BILLING"));

        SimulationRecommender.Recommendation result = recommender.recommend(request(null, 55L)).recommendation();

        assertThat(result.userCardId()).isEqualTo(CARD);
        assertThat(result.benefit().netBenefit()).isEqualByComparingTo("30000.5");
        assertThat(result.benefit().netBenefit().toPlainString()).isEqualTo("30000.5");
        assertThat(result.benefit().instantDiscount()).isEqualTo(30_000L);
        assertThat(result.benefit().benefits()).extracting(SimulationRecommender.BenefitItem::realization)
                .containsExactly("AT_APPROVAL", "AT_BILLING");
        // 대안: 계산한 다른 카드(1E+3이 아닌 1000) + 비교하지 못한 카드. 엔진이 제외한 카드는 넣지 않는다
        assertThat(result.alternatives()).extracting(SimulationRecommender.Alternative::userCardId)
                .containsExactly(OTHER, UNCOMPARED);
        assertThat(result.alternatives().get(0).netBenefit().toPlainString()).isEqualTo("1000");
        assertThat(result.alternatives().get(0).nickname()).isEqualTo("카드 11");
        assertThat(result.alternatives().get(1).netBenefit()).isNull();
        assertThat(result.alternatives().get(1).note()).startsWith("혜택 정보를 확인하지 못해");
        assertThat(result.alternatives()).noneMatch(SimulationRecommender.Alternative::recommended);
        // 가정: 구매 시각 + 그 카드·공통 규칙만. 브랜드를 골랐으니 브랜드 안내는 없다
        assertThat(result.assumptions()).extracting(SimulationRecommender.Assumption::id)
                .containsExactly("YAMU_PURCHASE_TIME_NOON", "RULE_CARD", "RULE_COMMON");
        assertThat(result.missingInformation()).isEmpty();
        assertThat(result.performanceImpact()).isNull();
    }

    @Test
    void 거래는_구매일_낮_12시_KST와_요청한_결제_방식으로_만들고_브랜드가_있으면_브랜드_혜택을_빼지_않는다() {
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, zero())), List.of(calculated(CARD, zero()))),
                List.of(), List.of(), Map.of());

        recommender.recommend(request(null, 55L));

        ArgumentCaptor<RecommendationInput.Transaction> transaction =
                ArgumentCaptor.forClass(RecommendationInput.Transaction.class);
        ArgumentCaptor<CalculationOptions> options = ArgumentCaptor.forClass(CalculationOptions.class);
        verify(engine).recommendSimulation(eq(USER), transaction.capture(), options.capture());
        assertThat(transaction.getValue().occurredAt()).isEqualTo(Instant.parse("2026-11-10T03:00:00Z"));
        assertThat(transaction.getValue().amount()).isEqualTo(300_000L);
        assertThat(transaction.getValue().merchantId()).isEqualTo(55L);
        assertThat(transaction.getValue().merchantCategoryCode()).isEqualTo("SUPERMARKET");
        assertThat(transaction.getValue().facts()).containsEntry("CHANNEL:OFFLINE", true)
                .containsEntry("CHANNEL:ONLINE", false);
        // 브랜드를 골랐으니 POS 결제와 같은 거래 사실을 싣는다(2026-11-08)
        assertThat(transaction.getValue().facts()).containsAllEntriesOf(PosPurchaseFacts.verified());
        assertThat(options.getValue().excludeBrandDependentBenefits()).isFalse();
    }

    @Test
    void 일반_업종만_고르면_승인된_일반_구매_사실만_보완한다() {
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, zero())), List.of(calculated(CARD, zero()))),
                List.of(), List.of(), Map.of());

        recommender.recommend(request(null, null));

        ArgumentCaptor<RecommendationInput.Transaction> transaction =
                ArgumentCaptor.forClass(RecommendationInput.Transaction.class);
        verify(engine).recommendSimulation(eq(USER), transaction.capture(), any());
        // 일반 업종의 모의 가정은 적용하되 POS만 확인하는 사실까지 복사하지 않는다.
        assertThat(transaction.getValue().facts()).containsEntry("TRANSACTION_KIND:ELECTRICITY_BILL", false)
                .doesNotContainKeys("TRANSACTION_KIND:LPG_STATION", "PERFORMANCE:POSTPAID_TRANSIT");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ELECTRIC_UTILITY,", "UNIVERSITY,", "TAXI,",
            "ELECTRIC_UTILITY,55", "UNIVERSITY,55", "TAXI,55"})
    void 제외_업종은_브랜드_유무와_관계없이_일반_구매_사실을_싣지_않는다(String category, Long merchant) {
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, zero())), List.of(calculated(CARD, zero()))),
                List.of(), List.of(), Map.of());

        // 지금 시드에는 없지만 카탈로그에 전기요금 브랜드가 생기면: ELECTRICITY_BILL=false를 확정할 수 없다
        recommender.recommend(new SimulationRecommender.Request(USER, null, PaymentTerms.lumpSum(300_000L), "OFFLINE", merchant,
                category, PURCHASE_DATE, TODAY));

        ArgumentCaptor<RecommendationInput.Transaction> transaction =
                ArgumentCaptor.forClass(RecommendationInput.Transaction.class);
        verify(engine).recommendSimulation(eq(USER), transaction.capture(), any());
        assertThat(transaction.getValue().merchantId()).isEqualTo(merchant);
        assertThat(transaction.getValue().facts()).isEmpty();
        assertThat(transaction.getValue().simulationRules()).isEmpty();
    }

    @Test
    void 브랜드를_고르지_않으면_브랜드_혜택을_빼고_계산을_끝냈을_때만_안내한다() {
        Calculation withoutBrand = new Calculation(true, new BigDecimal("500"),
                List.of(benefit(1, BenefitType.CASHBACK, "500", "500")), List.of(), List.of(), List.of(), List.of(),
                List.of(new ExcludedBenefit(9L, "BRAND_MISSING")), List.of());
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, withoutBrand)),
                List.of(calculated(CARD, withoutBrand))), List.of(), List.of(), Map.of(1L, "AT_BILLING"));

        SimulationRecommender.Recommendation result = recommender.recommend(request(null, null)).recommendation();

        ArgumentCaptor<CalculationOptions> options = ArgumentCaptor.forClass(CalculationOptions.class);
        verify(engine).recommendSimulation(anyLong(), any(), options.capture());
        assertThat(options.getValue().excludeBrandDependentBenefits()).isTrue();
        assertThat(result.assumptions()).extracting(SimulationRecommender.Assumption::id)
                .containsExactly("YAMU_PURCHASE_TIME_NOON", "YAMU_BRAND_NOT_SELECTED");
    }

    @Test
    void 계산을_끝내지_못한_카드는_브랜드_안내_없이_혜택이_null이고_빠진_정보를_싣는다() {
        Calculation incomplete = new Calculation(false, null, List.of(), List.of(), List.of(new Problem(1L, "MISSING_FACT")),
                List.of(), List.of(new MissingInformation(1L, "FACT_UNKNOWN", "WEEKDAY")),
                List.of(new ExcludedBenefit(9L, "BRAND_MISSING")), List.of());
        stub(selection(Status.DATA_INSUFFICIENT, null, List.of(),
                        List.of(new CardEvaluation(CARD, CardStatus.NOT_COMPARED, "BENEFIT_UNRESOLVED", incomplete))),
                List.of(), List.of(), Map.of());

        SimulationRecommender.Recommendation result = recommender.recommend(request(CARD, null)).recommendation();

        assertThat(result.benefit()).isNull();
        assertThat(result.missingInformation()).containsExactly(
                new SimulationRecommender.MissingInformation("BENEFIT_UNRESOLVED", null),
                new SimulationRecommender.MissingInformation("FACT_UNKNOWN", "WEEKDAY"),
                new SimulationRecommender.MissingInformation("MISSING_FACT", null));
        assertThat(result.assumptions()).extracting(SimulationRecommender.Assumption::id)
                .containsExactly("YAMU_PURCHASE_TIME_NOON");
    }

    @Test
    void 배분이_안_정해진_즉시할인_묶음은_원화_합계를_즉시할인에_더한다() {
        Calculation calculation = new Calculation(true, new BigDecimal("5000"), List.of(
                benefit(1, BenefitType.DISCOUNT, "3000", null), benefit(2, BenefitType.DISCOUNT, "4000", null)),
                List.of(), List.of(),
                List.of(new UnallocatedBenefitGroup(List.of(1L, 2L), 77L, new BigDecimal("5000"), Unit.KRW,
                        "SHARED_LIMIT_ALLOCATION_UNRESOLVED")));
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, calculation)), List.of(calculated(CARD, calculation))),
                List.of(), List.of(), Map.of(1L, "AT_APPROVAL", 2L, "AT_APPROVAL"));

        SimulationRecommender.Benefit benefit = recommender.recommend(request(null, 55L)).recommendation().benefit();

        assertThat(benefit.instantDiscount()).isEqualTo(5_000L);
        assertThat(benefit.benefits()).extracting(SimulationRecommender.BenefitItem::amount).containsOnlyNulls();
    }

    @Test
    void 할인_혜택의_적용_시점을_모르면_즉시할인을_정할_수_없어_혜택이_null이다() {
        Calculation calculation = new Calculation(true, new BigDecimal("3000"),
                List.of(benefit(1, BenefitType.DISCOUNT, "3000", "3000")), List.of(), List.of());
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, calculation)), List.of(calculated(CARD, calculation))),
                List.of(), List.of(), Map.of());

        SimulationRecommender.Recommendation result = recommender.recommend(request(null, 55L)).recommendation();

        assertThat(result.benefit()).isNull();
        assertThat(result.missingInformation())
                .containsExactly(new SimulationRecommender.MissingInformation("INSTANT_DISCOUNT_TIMING_UNKNOWN", null));
    }

    @Test
    void 고른_카드가_오늘_잔액_부족으로_제외되면_혜택만_비우고_그_카드와_공통_문제만_싣는다() {
        stub(selection(Status.READY, OTHER, List.of(calculated(OTHER, zero())),
                        List.of(new CardEvaluation(CARD, CardStatus.EXCLUDED, "INSUFFICIENT_BALANCE", null),
                                calculated(OTHER, zero()))),
                List.of(new RecommendationDataIssue(CARD, null, "CREDIT_LIMIT_CHECK_SKIPPED", false),
                        new RecommendationDataIssue(OTHER, null, "OTHER_CARD_PROBLEM", false),
                        new RecommendationDataIssue(null, null, "COMMON_PROBLEM", false)),
                List.of(), Map.of());

        SimulationRecommender.Recommendation result = recommender.recommend(request(CARD, 55L)).recommendation();

        assertThat(result.userCardId()).isEqualTo(CARD);
        assertThat(result.benefit()).isNull();
        // 신용한도 확인 생략은 응답의 creditLimit으로 보이므로 다시 싣지 않는다
        assertThat(result.missingInformation()).containsExactly(
                new SimulationRecommender.MissingInformation("INSUFFICIENT_BALANCE", null),
                new SimulationRecommender.MissingInformation("COMMON_PROBLEM", null));
        assertThat(result.alternatives()).extracting(SimulationRecommender.Alternative::userCardId).containsExactly(OTHER);
        // 사용자가 다른 카드를 골랐으니 엔진의 1순위 카드를 대안에서 알아볼 수 있게 표시한다(!155 리뷰)
        assertThat(result.alternatives().get(0).recommended()).isTrue();
    }

    @Test
    void 카드를_고르지_않았는데_엔진이_고른_카드가_없으면_추천할_수_없다() {
        stub(selection(Status.NO_ELIGIBLE_CARD, null, List.of(),
                List.of(new CardEvaluation(CARD, CardStatus.EXCLUDED, "INSUFFICIENT_BALANCE", null))),
                List.of(), List.of(), Map.of());
        assertThat(recommender.recommend(request(null, 55L)).unavailableReason()).isEqualTo("NO_ELIGIBLE_CARD");

        // 혜택 정보를 확인하지 못한 카드(계산 결과 있음)가 하나라도 있으면 혜택 정보 부족
        Calculation unresolved = new Calculation(false, null, List.of(), List.of(), List.of(new Problem(1L, "MISSING_FACT")));
        stub(selection(Status.DATA_INSUFFICIENT, null, List.of(),
                List.of(new CardEvaluation(CARD, CardStatus.NOT_COMPARED, "BENEFIT_UNRESOLVED", unresolved),
                        new CardEvaluation(OTHER, CardStatus.NOT_COMPARED, "BALANCE_READ_RETRYABLE", null))),
                List.of(), List.of(), Map.of());
        SimulationRecommender.Result result = recommender.recommend(request(null, 55L));
        assertThat(result.recommendation()).isNull();
        assertThat(result.unavailableReason()).isEqualTo("BENEFIT_DATA_INSUFFICIENT");
    }

    @Test
    void 모든_카드가_결제_가능_여부를_몰라_빠지면_혜택_정보_부족이_아니라_결제_가능_여부_미확인이다() {
        // 금융망 잔액 조회 실패처럼 계산 결과 없이 빠진 카드만 있으면 외부 실패를 혜택 정보 부족으로 보이지 않는다(!155 리뷰)
        stub(selection(Status.DATA_INSUFFICIENT, null, List.of(),
                List.of(new CardEvaluation(CARD, CardStatus.NOT_COMPARED, "BALANCE_READ_RETRYABLE", null),
                        new CardEvaluation(OTHER, CardStatus.NOT_COMPARED, "BALANCE_READ_RETRYABLE", null),
                        new CardEvaluation(EXCLUDED, CardStatus.EXCLUDED, "CARD_SUSPENDED", null))),
                List.of(), List.of(), Map.of());

        assertThat(recommender.recommend(request(null, 55L)).unavailableReason())
                .isEqualTo("PAYMENT_AVAILABILITY_UNKNOWN");
    }

    @Test
    void 다음_달_이후_구매면_전월_실적이_오늘까지_기준이라고_안내한다() {
        stub(selection(Status.READY, CARD, List.of(calculated(CARD, zero())), List.of(calculated(CARD, zero()))),
                List.of(), List.of(), Map.of());

        // 오늘 11/6, 구매 12/3: 엔진의 "전월"은 아직 진행 중인 11월
        assertThat(recommender.recommend(request(null, 55L, LocalDate.of(2026, 12, 3))).recommendation().assumptions())
                .extracting(SimulationRecommender.Assumption::id)
                .containsExactly("YAMU_PURCHASE_TIME_NOON", "YAMU_PREVIOUS_PERFORMANCE_AS_OF_TODAY");
        // 같은 달 구매(11/30)면 전월(10월)은 끝난 달이라 안내하지 않는다
        assertThat(recommender.recommend(request(null, 55L, LocalDate.of(2026, 11, 30))).recommendation().assumptions())
                .extracting(SimulationRecommender.Assumption::id).containsExactly("YAMU_PURCHASE_TIME_NOON");
    }

    @Test
    void 엔진이_DB를_읽지_못하면_카드를_골랐을_때만_혜택_없이_잔고를_비교한다() {
        when(engine.recommendSimulation(anyLong(), any(), any()))
                .thenThrow(new RecommendationService.RecommendationReadException(null));

        assertThat(recommender.recommend(request(null, 55L)).unavailableReason()).isEqualTo("RECOMMENDATION_UNAVAILABLE");
        SimulationRecommender.Recommendation chosen = recommender.recommend(request(CARD, 55L)).recommendation();
        assertThat(chosen.benefit()).isNull();
        assertThat(chosen.missingInformation())
                .containsExactly(new SimulationRecommender.MissingInformation("RECOMMENDATION_READ_FAILED", null));
    }

    private void stub(RecommendationSelector.Result selection, List<RecommendationDataIssue> issues,
            List<SimulationRule> rules, Map<Long, String> timing) {
        Map<Long, RecommendationService.CardContext> cards = Map.of(
                CARD, context("내 카드", timing), OTHER, context(null, Map.of()),
                UNCOMPARED, context("", Map.of()), EXCLUDED, context(null, Map.of()));
        when(engine.recommendSimulation(anyLong(), any(), any()))
                .thenReturn(new RecommendationService.Result(selection, issues, cards, rules));
    }

    private static RecommendationService.CardContext context(String alias, Map<Long, String> timing) {
        return new RecommendationService.CardContext(1L, "카드 11", alias, "1234", CardType.CREDIT, "ISSUER", false,
                timing);
    }

    private static RecommendationSelector.Result selection(Status status, Long selected, List<CardEvaluation> ranked,
            List<CardEvaluation> evaluated) {
        return new RecommendationSelector.Result(null, status, selected,
                selected == null ? null : RecommendationSelector.SelectionReason.MAXIMUM_NET_BENEFIT, null, false, false,
                ranked, evaluated);
    }

    private static CardEvaluation calculated(long cardId, Calculation calculation) {
        return new CardEvaluation(cardId, CardStatus.CALCULATED, null, calculation);
    }

    private static Calculation zero() {
        return new Calculation(true, BigDecimal.ZERO, List.of(), List.of(), List.of());
    }

    private static BenefitValue benefit(long id, BenefitType type, String raw, String value) {
        return new BenefitValue(id, 1, "혜택 " + id, type, new BigDecimal(raw), value == null ? null : new BigDecimal(value),
                Unit.KRW, value == null ? null : new BigDecimal(value), null);
    }

    private static SimulationRecommender.Request request(Long userCardId, Long merchantId) {
        return request(userCardId, merchantId, PURCHASE_DATE);
    }

    private static SimulationRecommender.Request request(Long userCardId, Long merchantId, LocalDate purchaseDate) {
        return new SimulationRecommender.Request(USER, userCardId, PaymentTerms.lumpSum(300_000L), "OFFLINE", merchantId,
                "SUPERMARKET", purchaseDate, TODAY);
    }
}
