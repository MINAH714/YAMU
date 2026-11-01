package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 잔고예보 위에 가상 구매 출금을 얹어 전후를 비교한다(Task 7.2.2). DB·외부 호출 없이 입력만으로 계산하고 입력을 바꾸지 않는다.
 *
 * <p>구매 출금은 산다고 가정한 돈이라 확정 선부터 반영한다. 그래서 구매만으로 확정 선이 안전 잔액 아래로 내려가면 DANGER다.
 * 출금은 카드의 실제 출금 계좌에만 넣고, 그 계좌가 선택 밖이면 통합 비교에 넣지 않고 따로 알린다.
 * 생활비는 잔고예보와 똑같이 계좌마다 있는 대로 쓴다. 그래서 구매 전 결과가 잔고예보와 같고, 한 계좌의 생활비 위험을
 * 다른 계좌의 자료 부족 때문에 숨기지 않는다. 대신 생활비가 일부 계좌에만 있으면 통합은 예상 선, 그 계좌는 보수적 선이라
 * 계좌별 금액의 합이 통합과 다를 수 있다(잔고예보 계좌 상세와 같은 한계).
 */
final class PurchaseSimulator {

    private static final String OUTSIDE_SELECTION_NOTE = "선택하지 않은 계좌에서 빠져 비교 결과에는 넣지 않았어요.";

    private PurchaseSimulator() {
    }

    /**
     * 가상 구매 전후 비교를 계산한다.
     *
     * @param input 구매 전 예보 재료와 가상 구매 출금 회차
     * @return 출금 일정, 통합·계좌별 전후 비교, 날짜별 전후 잔액
     * @throws IllegalArgumentException 과거 구매일, 빈 회차, 0원 이하 회차, 구매일보다 이른 출금일, 출금 계좌 없음
     */
    static PurchaseSimulation simulate(Input input) {
        validate(input);
        boolean selected = input.startBalances().containsKey(input.withdrawalAccountId());

        List<PurchaseSimulation.ScheduledWithdrawal> schedule = new ArrayList<>();
        List<FlowEvent> afterEvents = new ArrayList<>(input.beforeEvents());
        long within = 0;
        long beyond = 0;
        long total = 0;
        for (BillingSchedule.Withdrawal withdrawal : input.withdrawals()) {
            boolean withinPeriod = selected && !withdrawal.withdrawalDate().isAfter(input.to());
            String label = label(withdrawal);
            schedule.add(new PurchaseSimulation.ScheduledWithdrawal(withdrawal.withdrawalDate(), -withdrawal.amount(),
                    label, withdrawal.installmentNo(), withdrawal.installmentMonths(), withdrawal.billingMonth(),
                    input.withdrawalAccountId(), withinPeriod));
            total = Math.addExact(total, withdrawal.amount());
            if (!selected) {
                continue;
            }
            if (withinPeriod) {
                within = Math.subtractExact(within, withdrawal.amount());
                afterEvents.add(new FlowEvent(input.withdrawalAccountId(), withdrawal.withdrawalDate(),
                        -withdrawal.amount(), FlowEvent.Type.SIMULATED_PURCHASE, FlowEvent.Confidence.CONFIRMED,
                        label, null));
            } else {
                beyond = Math.subtractExact(beyond, withdrawal.amount());
            }
        }

        ForecastCalculator.Projection before = ForecastCalculator.project(input.startBalances(), input.beforeEvents(),
                input.from(), input.to(), input.dailyLivingCost());
        ForecastCalculator.Projection after = ForecastCalculator.project(input.startBalances(), afterEvents,
                input.from(), input.to(), input.dailyLivingCost());
        Side beforeSide = assess(before, input.alertThreshold());
        Side afterSide = assess(after, input.alertThreshold());

        List<PurchaseSimulation.SeriesPoint> series = new ArrayList<>();
        for (int i = 0; i < before.total().size(); i++) {
            series.add(new PurchaseSimulation.SeriesPoint(before.total().get(i).date(),
                    RiskDetector.worstBalance(before.total().get(i)), RiskDetector.worstBalance(after.total().get(i))));
        }
        List<PurchaseSimulation.AccountComparison> accounts = input.startBalances().keySet().stream()
                .map(accountId -> new PurchaseSimulation.AccountComparison(accountId,
                        snapshot(beforeSide.byAccount().get(accountId), beforeSide.byAccount().get(accountId).status()),
                        snapshot(afterSide.byAccount().get(accountId), afterSide.byAccount().get(accountId).status())))
                .toList();
        PurchaseSimulation.OutsideSelectionImpact outside = selected ? null
                : new PurchaseSimulation.OutsideSelectionImpact(input.withdrawalAccountId(), total,
                        OUTSIDE_SELECTION_NOTE);

        return new PurchaseSimulation(List.copyOf(schedule),
                new PurchaseSimulation.Comparison(beforeSide.summary(), afterSide.summary(), within, beyond),
                List.copyOf(series), outside, accounts);
    }

    private static void validate(Input input) {
        if (input.withdrawalAccountId() == null) {
            throw new IllegalArgumentException("카드 출금 계좌가 없다.");
        }
        if (input.purchaseDate().isBefore(input.from())) {
            throw new IllegalArgumentException("과거 구매일이다: " + input.purchaseDate() + " < " + input.from());
        }
        if (input.withdrawals().isEmpty()) {
            throw new IllegalArgumentException("출금 회차가 없다.");
        }
        for (BillingSchedule.Withdrawal withdrawal : input.withdrawals()) {
            if (withdrawal.amount() <= 0) {
                throw new IllegalArgumentException("회차 금액은 1원 이상이어야 한다: " + withdrawal.amount());
            }
            if (withdrawal.withdrawalDate().isBefore(input.purchaseDate())) {
                throw new IllegalArgumentException("출금일이 구매일보다 이르다: " + withdrawal.withdrawalDate());
            }
        }
    }

    /** 신용 할부 "1/3회차 (11월 청구)", 신용 일시불 "일시불 (11월 청구)", 체크카드(청구 월 없음) "체크카드 즉시 출금". */
    private static String label(BillingSchedule.Withdrawal withdrawal) {
        if (withdrawal.billingMonth() == null) {
            return "체크카드 즉시 출금";
        }
        String billing = "(" + withdrawal.billingMonth().getMonthValue() + "월 청구)";
        if (withdrawal.installmentMonths() == 1) {
            return "일시불 " + billing;
        }
        return withdrawal.installmentNo() + "/" + withdrawal.installmentMonths() + "회차 " + billing;
    }

    /** 통합 요약(통합 선의 최저·마지막, 최종 수준)과 계좌별 판단. */
    private static Side assess(ForecastCalculator.Projection projection, long threshold) {
        RiskDetector.Assessment aggregate = RiskDetector.assess(projection.total(), threshold);
        Map<Long, RiskDetector.Assessment> byAccount = new LinkedHashMap<>();
        projection.byAccount().forEach((accountId, points) ->
                byAccount.put(accountId, RiskDetector.assess(points, threshold)));
        return new Side(snapshot(aggregate, RiskDetector.overall(aggregate, byAccount.values())), byAccount);
    }

    private static PurchaseSimulation.Snapshot snapshot(RiskDetector.Assessment assessment, RiskDetector.Level status) {
        return new PurchaseSimulation.Snapshot(assessment.minBalance(), assessment.minBalanceDate(),
                assessment.endBalance(), status);
    }

    private record Side(PurchaseSimulation.Snapshot summary, Map<Long, RiskDetector.Assessment> byAccount) {}

    /**
     * 가상 구매 비교 입력.
     *
     * @param startBalances       선택 계좌별 현재 잔액(원). 순서대로 결과에 담는다
     * @param beforeEvents        구매 전 예보의 미래 입출금. <b>이번 가상 구매가 들어 있으면 안 된다</b>(두 번 빠진다)
     * @param from                한국 날짜 오늘. 예보 시작일이자 과거 구매일을 가르는 기준이다
     * @param to                  비교 기간 마지막 날(포함)
     * @param dailyLivingCost     계좌별 추정 하루 생활비(원). 없는 계좌는 예상 선, 한 계좌라도 없으면 통합도 예상 선이다
     * @param alertThreshold      안전 잔액(원)
     * @param purchaseDate        가상 구매일(한국 날짜). from 이상이어야 한다
     * @param withdrawalAccountId 카드의 실제 출금 계좌. 선택 밖이어도 된다
     * @param withdrawals         {@link BillingSchedule}이 만든 출금 회차. 체크카드는 청구 월이 null인 한 건
     */
    record Input(Map<Long, Long> startBalances, List<FlowEvent> beforeEvents, LocalDate from, LocalDate to,
            Map<Long, Long> dailyLivingCost, long alertThreshold, LocalDate purchaseDate, Long withdrawalAccountId,
            List<BillingSchedule.Withdrawal> withdrawals) {}
}
