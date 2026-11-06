package com.yamu.be.forecast;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 가상 구매 시뮬레이션의 카드 추천·혜택 계산으로 가는 문(포트). 결제 요청 없이 부른다(Task 7.2.2).
 *
 * <p>POS용 추천({@code CardRecommender})은 결제 요청 번호가 있어야 혜택을 계산해서 시뮬레이션에 쓸 수 없다. 결제 요청 없이
 * 기존 혜택 계산을 부르는 방법은 BE 2·BE 3가 정하고(2026-11-05 BE 2 안내), 정해지면 이 문의 실제 구현을 붙인다.
 * 그 전에는 {@link UnconnectedSimulationRecommender}가 "계산할 수 없음"을 돌려준다.
 *
 * <p>읽기만 한다. 승인·청구·실적·혜택 사용량·신용한도를 바꾸지 않는다. 체크카드 추천은 금융망 잔액을 조회할 수 있어
 * DB 트랜잭션 밖에서 부른다.
 */
public interface SimulationRecommender {

    /**
     * 카드별 혜택을 계산해 추천 카드(또는 지정 카드의 혜택)를 돌려준다.
     *
     * @param request 사용자·결제 조건·물품/업종/가맹점·구매일. userCardId가 있으면 그 카드의 혜택만 계산한다
     * @return 계산 결과. 추천 엔진이 아직 연결되지 않았거나 계산할 수 없으면 empty
     */
    Optional<Recommendation> recommend(Request request);

    /**
     * @param userCardId           사용자가 고른 카드. null이면 추천 1순위를 고른다
     * @param terms                결제 금액·결제 방식·할부 개월 수(검증된 값)
     * @param productCategoryCode  구매할 물품 카테고리. 없으면 null
     * @param merchantCategoryCode 구매 예정 가맹점 업종. 없으면 null
     * @param merchantId           구매 예정 브랜드의 가맹점. 없으면 null
     * @param purchaseDate         구매 예정일(한국 날짜). 요일·실적 월 판정 기준
     */
    record Request(long userId, Long userCardId, PaymentTerms terms, String productCategoryCode,
            String merchantCategoryCode, Long merchantId, LocalDate purchaseDate) {}

    /**
     * @param userCardId        계산한 카드(지정 카드 또는 추천 1순위)
     * @param benefit           그 카드의 예상 혜택. 조건·약관이 미확인이면 null(0원으로 확정하지 않음)
     * @param alternatives      같은 결제 방식·개월 수로 비교한 다른 보유 카드. 비교할 수 없으면 null
     * @param performanceImpact 그 카드 구매 월 실적에 미치는 영향. 모르면 null
     */
    record Recommendation(long userCardId, Benefit benefit, List<Alternative> alternatives,
            PerformanceImpact performanceImpact) {}

    /**
     * @param netBenefit    혜택 합계(원)
     * @param chargedAmount 즉시할인을 뺀, 실제로 빠지거나 청구될 금액(원). 1원 이상 결제 금액 이하
     */
    record Benefit(long netBenefit, List<BenefitItem> benefits, long chargedAmount) {}

    /** @param type 혜택 종류(예: BILLING_DISCOUNT, INSTANT_DISCOUNT, POINT) */
    record BenefitItem(Long benefitId, String name, String type, long amount) {}

    record Alternative(long userCardId, String nickname, CardType cardType, String plan, Integer installmentMonths,
            Long netBenefit, String note) {}

    /** @param meetsThresholdAfter 구매 뒤 그달 실적 기준을 넘는지 */
    record PerformanceImpact(YearMonth yearMonth, long recognizedAmount, boolean meetsThresholdAfter) {}
}
