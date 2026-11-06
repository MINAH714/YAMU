package com.yamu.be.forecast;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import java.time.LocalDate;
import java.util.List;

/**
 * 가상 구매 시뮬레이션 응답(명세 「가상 구매 시뮬레이션」). 계산만 한 결과라 결제·청구·실적·혜택 사용량·한도는 바뀌지 않는다.
 *
 * @param benefit                예상 혜택. 추천 엔진 연결 전·조건 미확인이면 null(0원으로 확정하지 않음)
 * @param schedule               가상 구매의 모든 출금 회차(기간 밖 포함)
 * @param comparison             선택 계좌 통합 기준 전후 비교
 * @param series                 통합의 날짜별 전후 잔액(가장 나쁜 선 기준)
 * @param performanceImpact      구매 월 실적 영향. 모르면 null
 * @param alternatives           다른 보유 카드 비교. 모르면 null
 * @param creditLimit            신용카드 한도 확인 결과(명세에 없음, 2026-11-05 추가). 체크카드는 null
 * @param outsideSelectionImpact 카드 출금 계좌가 선택 밖이면 그 영향. 선택 안이면 null
 * @param accountComparisons     계좌별 전후 비교. includeAccountDetails=true일 때만
 */
public record SimulationResponse(Input input, Card card, SimulationRecommender.Benefit benefit,
        List<Schedule> schedule, PurchaseSimulation.Comparison comparison, List<PurchaseSimulation.SeriesPoint> series,
        SimulationRecommender.PerformanceImpact performanceImpact, List<SimulationRecommender.Alternative> alternatives,
        CreditLimit creditLimit, PurchaseSimulation.OutsideSelectionImpact outsideSelectionImpact,
        List<PurchaseSimulation.AccountComparison> accountComparisons, String message, boolean isEstimate) {

    /** 요청에서 계산에 쓴 값(구매일은 기본값을 채운 값). */
    public record Input(long amount, String productCategoryCode, String productName, String merchantCategoryCode,
            Long merchantId, LocalDate purchaseDate, PaymentPlan plan, Integer installmentMonths) {}

    /** @param recommended 사용자가 고르지 않아 추천 1순위로 계산했으면 true */
    public record Card(long userCardId, String nickname, CardType cardType, boolean recommended) {}

    /** 출금 한 회차. amount는 음수(출금)다. */
    public record Schedule(LocalDate date, long amount, String label) {}

    /**
     * @param status         SKIPPED: 모의 신용한도를 저장·조회하지 않아 확인하지 않았음("신용한도 확인 생략")
     * @param availableLimit 가용 한도(원). 확인하지 않았으면 null(0원·무제한으로 보지 않음)
     */
    public record CreditLimit(String status, Long availableLimit) {}
}
