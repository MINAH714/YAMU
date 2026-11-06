package com.yamu.be.forecast;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 가상 구매 시뮬레이션의 카드 추천·혜택 계산으로 가는 문(포트). 결제 요청 없이 부른다(Task 7.2.2).
 * 실제 구현은 추천 엔진({@code RecommendationService.recommendSimulation})을 부르는 {@link EngineSimulationRecommender}다.
 *
 * <p>읽기만 한다. 승인·청구·실적·혜택 사용량·신용한도를 바꾸지 않는다. 체크카드 추천은 금융망 잔액을 조회할 수 있어
 * DB 트랜잭션 밖에서 부른다.
 */
public interface SimulationRecommender {
    /**
     * 카드별 혜택을 계산해 추천 카드(또는 지정 카드의 혜택)를 돌려준다.
     *
     * @param request 사용자·결제 조건·결제 채널·확인된 가맹점/업종·구매일. userCardId가 있으면 그 카드의 혜택을 쓴다
     * @return 계산 결과. 카드를 고르지 않았는데 추천할 카드가 없으면 {@link Result#unavailable}
     */
    Result recommend(Request request);

    /**
     * @param userCardId           사용자가 고른 카드. null이면 추천 1순위를 고른다
     * @param terms                결제 금액·결제 방식·할부 개월 수(검증된 값)
     * @param paymentChannel       ONLINE·OFFLINE
     * @param merchantId           확인된 브랜드 가맹점. 고르지 않았으면 null(브랜드 혜택을 빼고 계산)
     * @param merchantCategoryCode 확인된 업종(브랜드를 고르면 그 브랜드의 업종)
     * @param purchaseDate         구매 예정일(한국 날짜). 요일·실적 월 판정 기준
     */
    record Request(long userId, Long userCardId, PaymentTerms terms, String paymentChannel, Long merchantId,
            String merchantCategoryCode, LocalDate purchaseDate) {}

    /**
     * @param recommendation    계산 결과. 추천할 카드가 없으면 null
     * @param unavailableReason 카드를 고르지 않았는데 추천할 카드가 없을 때의 이유(NO_ELIGIBLE_CARD·BENEFIT_DATA_INSUFFICIENT·
     *                          RECOMMENDATION_UNAVAILABLE). 결과가 있으면 null
     */
    record Result(Recommendation recommendation, String unavailableReason) {
        static Result of(Recommendation recommendation) {
            return new Result(recommendation, null);
        }

        static Result unavailable(String reason) {
            return new Result(null, reason);
        }
    }

    /**
     * @param userCardId         계산한 카드(지정 카드 또는 추천 1순위)
     * @param benefit            그 카드의 예상 혜택. 계산을 끝내지 못했으면 null(0원으로 확정하지 않음)
     * @param alternatives       같은 결제 방식·개월 수로 비교한 다른 보유 카드. 비교할 수 없으면 null
     * @param performanceImpact  그 카드 구매 월 실적에 미치는 영향. 엔진이 주지 않아 지금은 늘 null
     * @param assumptions        계산에 쓴 가정
     * @param missingInformation 판정하지 못한 정보(혜택이 null인 이유 포함)
     */
    record Recommendation(long userCardId, Benefit benefit, List<Alternative> alternatives,
            PerformanceImpact performanceImpact, List<Assumption> assumptions,
            List<MissingInformation> missingInformation) {}

    /**
     * @param netBenefit      혜택 합계(원, 소수 그대로. 결제 추천과 같음)
     * @param instantDiscount 승인 때 바로 빠지는 즉시할인 합계(원). 청구 금액 = 결제 금액 − 이 값
     */
    record Benefit(BigDecimal netBenefit, List<BenefitItem> benefits, long instantDiscount) {}

    /**
     * 결제 추천의 혜택 줄과 같은 모양이다.
     *
     * @param type        DISCOUNT·CASHBACK·POINT
     * @param realization 적용 시점(AT_APPROVAL 즉시할인, 그 밖은 청구·적립 등). 모르면 null
     * @param amount      원화 금액. 공유 한도 배분이 정해지지 않았으면 null
     */
    record BenefitItem(Long benefitId, String name, String type, String realization, BigDecimal amount) {}

    /** @param netBenefit 비교하지 못한 카드는 null이고 note에 이유가 있다 */
    record Alternative(long userCardId, String nickname, CardType cardType, String plan, Integer installmentMonths,
            BigDecimal netBenefit, String note) {}

    /** @param meetsThresholdAfter 구매 뒤 그달 실적 기준을 넘는지 */
    record PerformanceImpact(YearMonth yearMonth, long recognizedAmount, boolean meetsThresholdAfter) {}

    /** 계산에 쓴 가정. id는 엔진의 모의 보완 규칙 id이거나 YAMU_로 시작하는 시뮬레이션 자체 가정이다. */
    record Assumption(String id, String description) {}

    /** @param field 판정하지 못한 거래 사실이나 요청 칸. 없으면 null */
    record MissingInformation(String code, String field) {}
}
