package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.forecast.BillingSchedule.Withdrawal;
import com.yamu.be.forecast.PurchaseSimulation.AccountComparison;
import com.yamu.be.forecast.PurchaseSimulation.ScheduledWithdrawal;
import com.yamu.be.forecast.PurchaseSimulation.SeriesPoint;
import com.yamu.be.forecast.PurchaseSimulation.Snapshot;
import com.yamu.be.forecast.RiskDetector.Level;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PurchaseSimulatorTest {
    private static final long THRESHOLD = 100_000L;
    private static final Long A = 3L;
    private static final Long B = 5L;

    @Test
    void 명세_예시_120만_원_3개월_할부는_기간_안_1회차만_잔액에_반영하고_나머지는_기간_밖_영향이다() {
        // 명세 예시: 10/24 구매, 30일 비교, 11/16·12/14·1/14에 40만 원씩. 11/16 다른 출금으로 구매 전 최저 412,000원
        LocalDate from = LocalDate.of(2026, 10, 24);
        List<FlowEvent> before = List.of(outflow(A, LocalDate.of(2026, 11, 16), -838_000));
        List<Withdrawal> withdrawals = List.of(
                credit(1, 3, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 16), 400_000),
                credit(2, 3, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 14), 400_000),
                credit(3, 3, YearMonth.of(2027, 1), LocalDate.of(2027, 1, 14), 400_000));

        PurchaseSimulation result = PurchaseSimulator.simulate(input(Map.of(A, 1_250_000L), before, from,
                from.plusDays(30), Map.of(), from, A, withdrawals));

        assertThat(result.schedule())
                .extracting(ScheduledWithdrawal::date, ScheduledWithdrawal::amount, ScheduledWithdrawal::label,
                        ScheduledWithdrawal::withinPeriod)
                .containsExactly(
                        tuple(LocalDate.of(2026, 11, 16), -400_000L, "1/3회차 (11월 청구)", true),
                        tuple(LocalDate.of(2026, 12, 14), -400_000L, "2/3회차 (12월 청구)", false),
                        tuple(LocalDate.of(2027, 1, 14), -400_000L, "3/3회차 (1월 청구)", false));
        assertThat(result.comparison().withinPeriodImpact()).isEqualTo(-400_000L);
        assertThat(result.comparison().beyondPeriodImpact()).isEqualTo(-800_000L);
        assertThat(result.comparison().before())
                .isEqualTo(new Snapshot(412_000L, LocalDate.of(2026, 11, 16), 412_000L, Level.SAFE));
        // 가상 구매는 확정 선에 넣으므로, 구매만으로 확정 선이 안전 잔액 아래면 DANGER다.
        assertThat(result.comparison().after())
                .isEqualTo(new Snapshot(12_000L, LocalDate.of(2026, 11, 16), 12_000L, Level.DANGER));
        assertThat(seriesOn(result, from)).isEqualTo(new SeriesPoint(from, 1_250_000L, 1_250_000L));
        assertThat(seriesOn(result, LocalDate.of(2026, 11, 16)))
                .isEqualTo(new SeriesPoint(LocalDate.of(2026, 11, 16), 412_000L, 12_000L));
        assertThat(result.outsideSelectionImpact()).isNull();
        assertThat(result.accountComparisons()).singleElement()
                .satisfies(account -> assertThat(account.after().status()).isEqualTo(Level.DANGER));
    }

    @Test
    void 구매일이_오늘보다_과거면_신용카드_출금일이_미래여도_거절한다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        List<Withdrawal> withdrawals = List.of(credit(1, 1, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 16),
                100_000));

        assertThatThrownBy(() -> PurchaseSimulator.simulate(input(Map.of(A, 1_000_000L), List.of(), today,
                today.plusDays(30), Map.of(), LocalDate.of(2026, 10, 25), A, withdrawals)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("과거 구매일");
    }

    @Test
    void 기간_뒤의_미래_구매는_정상이고_모두_기간_밖_영향이라_전후_잔액이_같다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        List<Withdrawal> withdrawals = List.of(credit(1, 1, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 14),
                250_000));

        PurchaseSimulation result = PurchaseSimulator.simulate(input(Map.of(A, 1_000_000L), List.of(), today, to,
                Map.of(), LocalDate.of(2026, 12, 5), A, withdrawals));

        assertThat(result.schedule()).singleElement().satisfies(withdrawal -> {
            assertThat(withdrawal.label()).isEqualTo("일시불 (12월 청구)");
            assertThat(withdrawal.withinPeriod()).isFalse();
        });
        assertThat(result.comparison().withinPeriodImpact()).isZero();
        assertThat(result.comparison().beyondPeriodImpact()).isEqualTo(-250_000L);
        assertThat(result.comparison().after()).isEqualTo(result.comparison().before());
        assertThat(result.series()).allMatch(point -> point.before() == point.after());
    }

    @Test
    void 오늘_체크카드_구매는_오늘부터_한_번만_빠진다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        List<Withdrawal> withdrawals = List.of(check(today, 300_000));

        PurchaseSimulation result = PurchaseSimulator.simulate(input(Map.of(A, 1_000_000L), List.of(), today,
                today.plusDays(7), Map.of(), today, A, withdrawals));

        assertThat(result.schedule()).extracting(ScheduledWithdrawal::label, ScheduledWithdrawal::billingMonth,
                        ScheduledWithdrawal::installmentMonths)
                .containsExactly(tuple("체크카드 즉시 출금", null, 1));
        assertThat(result.series().get(0)).isEqualTo(new SeriesPoint(today, 1_000_000L, 700_000L));
        // 다음 날부터도 차이는 30만 원 그대로다(하루에 한 번만 반영).
        assertThat(result.series()).allMatch(point -> point.before() - point.after() == 300_000L);
        assertThat(result.comparison().withinPeriodImpact()).isEqualTo(-300_000L);
    }

    @Test
    void 출금일이_기간_마지막_날이면_기간_안이고_다음_날이면_기간_밖이다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        LocalDate to = today.plusDays(14);

        PurchaseSimulation onLastDay = PurchaseSimulator.simulate(input(Map.of(A, 1_000_000L), List.of(), today, to,
                Map.of(), today, A, List.of(credit(1, 1, YearMonth.of(2026, 11), to, 100_000))));
        PurchaseSimulation dayAfter = PurchaseSimulator.simulate(input(Map.of(A, 1_000_000L), List.of(), today, to,
                Map.of(), today, A, List.of(credit(1, 1, YearMonth.of(2026, 11), to.plusDays(1), 100_000))));

        assertThat(onLastDay.comparison().withinPeriodImpact()).isEqualTo(-100_000L);
        assertThat(onLastDay.comparison().beyondPeriodImpact()).isZero();
        assertThat(onLastDay.series().get(onLastDay.series().size() - 1))
                .isEqualTo(new SeriesPoint(to, 1_000_000L, 900_000L));
        assertThat(dayAfter.comparison().withinPeriodImpact()).isZero();
        assertThat(dayAfter.comparison().beyondPeriodImpact()).isEqualTo(-100_000L);
        assertThat(dayAfter.series()).allMatch(point -> point.before() == point.after());
    }

    @Test
    void 카드_출금_계좌가_선택_밖이면_비교에_넣지_않고_전체_금액을_양수로_알린다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        Long unselected = 9L;
        List<Withdrawal> withdrawals = List.of(
                credit(1, 2, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 16), 300_000),
                credit(2, 2, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 14), 300_001));

        PurchaseSimulation result = PurchaseSimulator.simulate(input(Map.of(A, 1_000_000L), List.of(), today,
                today.plusDays(30), Map.of(), today, unselected, withdrawals));

        assertThat(result.comparison().withinPeriodImpact()).isZero();
        assertThat(result.comparison().beyondPeriodImpact()).isZero();
        assertThat(result.comparison().after()).isEqualTo(result.comparison().before());
        assertThat(result.series()).allMatch(point -> point.before() == point.after());
        assertThat(result.outsideSelectionImpact().accountId()).isEqualTo(unselected);
        assertThat(result.outsideSelectionImpact().amount()).isEqualTo(600_001L);
        assertThat(result.schedule()).allMatch(withdrawal -> !withdrawal.withinPeriod()
                && withdrawal.accountId().equals(unselected) && withdrawal.amount() < 0);
    }

    @Test
    void 통합_최저_잔액은_계좌별_최저의_합이_아니라_통합_일별_선에서_구한다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        // A는 5일에 100,000원, B는 10일에 50,000원으로 최저. 같은 날 최저가 아니라 통합 최저는 10일 550,000원이다.
        List<FlowEvent> before = List.of(
                outflow(A, today.plusDays(5), -400_000), income(A, today.plusDays(6), 400_000),
                outflow(B, today.plusDays(10), -450_000), income(B, today.plusDays(11), 450_000));
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(A, 500_000L);
        balances.put(B, 500_000L);

        PurchaseSimulation result = PurchaseSimulator.simulate(input(balances, before, today, today.plusDays(14),
                Map.of(), today, A, List.of(check(today, 10_000))));

        assertThat(result.comparison().before().minBalance()).isEqualTo(550_000L);
        assertThat(result.comparison().before().minBalanceDate()).isEqualTo(today.plusDays(10));
        assertThat(result.comparison().before().minBalance())
                .isEqualTo(result.series().stream().mapToLong(SeriesPoint::before).min().orElseThrow());
        assertThat(result.accountComparisons())
                .extracting(AccountComparison::accountId, c -> c.before().minBalance(), c -> c.before().minBalanceDate())
                .containsExactly(tuple(A, 100_000L, today.plusDays(5)), tuple(B, 50_000L, today.plusDays(10)));
        // 구매는 출금 계좌 A에만 반영된다.
        assertThat(result.accountComparisons().get(0).after().endBalance()).isEqualTo(490_000L);
        assertThat(result.accountComparisons().get(1).after()).isEqualTo(result.accountComparisons().get(1).before());
        assertEndBalancesAddUp(result);
    }

    @Test
    void 생활비가_일부_계좌에만_있어도_그_계좌의_생활비_위험을_숨기지_않는다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(A, 150_000L);
        balances.put(B, 500_000L);

        PurchaseSimulation result = PurchaseSimulator.simulate(input(balances, List.of(), today, today.plusDays(10),
                Map.of(A, 10_000L), today, B, List.of(check(today, 10_000))));

        // A는 보수적 선이 6일째 90,000원으로 안전 잔액 미만이라 WARNING. B에 생활비 자료가 없어도 숨기지 않는다(잔고예보와 같다).
        AccountComparison a = result.accountComparisons().get(0);
        assertThat(a.before()).isEqualTo(new Snapshot(50_000L, today.plusDays(10), 50_000L, Level.WARNING));
        assertThat(a.after()).isEqualTo(a.before());
        assertThat(result.accountComparisons().get(1).after().endBalance()).isEqualTo(490_000L);
        // 통합은 B 자료가 없어 예상 선이지만, 최종 수준은 계좌 위험을 반영한다.
        assertThat(result.comparison().before()).isEqualTo(new Snapshot(650_000L, today, 650_000L, Level.WARNING));
        assertThat(result.comparison().after()).isEqualTo(new Snapshot(640_000L, today, 640_000L, Level.WARNING));
        assertThat(result.series()).allMatch(point -> point.before() == 650_000L && point.after() == 640_000L);
        // 한계: 통합은 예상 선, A는 보수적 선이라 계좌별 종료 잔액의 합(550,000원)이 통합(650,000원)과 다르다.
        assertThat(a.before().endBalance() + result.accountComparisons().get(1).before().endBalance())
                .isEqualTo(550_000L);
    }

    @Test
    void 생활비가_모든_계좌에_있으면_보수적_선으로_비교한다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(A, 500_000L);
        balances.put(B, 300_000L);

        PurchaseSimulation result = PurchaseSimulator.simulate(input(balances, List.of(), today, today.plusDays(10),
                Map.of(A, 1_000L, B, 2_000L), today, A, List.of(check(today, 50_000))));

        // 오늘 생활비는 현재 잔액에 이미 반영돼 있어 내일부터 하루 3,000원씩 뺀다.
        assertThat(result.series().get(0)).isEqualTo(new SeriesPoint(today, 800_000L, 750_000L));
        assertThat(result.series().get(10)).isEqualTo(new SeriesPoint(today.plusDays(10), 770_000L, 720_000L));
        assertThat(result.accountComparisons().get(0).after().endBalance()).isEqualTo(440_000L);
        assertEndBalancesAddUp(result);
    }

    @Test
    void 입력을_바꾸지_않고_같은_입력이면_같은_결과다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        List<FlowEvent> before = new ArrayList<>(List.of(outflow(A, today.plusDays(3), -100_000)));
        Map<Long, Long> balances = new LinkedHashMap<>(Map.of(A, 500_000L));
        Map<Long, Long> living = new LinkedHashMap<>(Map.of(A, 1_000L));
        List<Withdrawal> withdrawals = new ArrayList<>(List.of(
                credit(1, 2, YearMonth.of(2026, 11), today.plusDays(5), 100_000),
                credit(2, 2, YearMonth.of(2026, 12), today.plusDays(35), 100_000)));
        List<FlowEvent> beforeCopy = List.copyOf(before);
        Map<Long, Long> balancesCopy = Map.copyOf(balances);
        Map<Long, Long> livingCopy = Map.copyOf(living);
        List<Withdrawal> withdrawalsCopy = List.copyOf(withdrawals);
        PurchaseSimulator.Input input = input(balances, before, today, today.plusDays(30), living, today, A,
                withdrawals);

        PurchaseSimulation first = PurchaseSimulator.simulate(input);
        PurchaseSimulation second = PurchaseSimulator.simulate(input);

        assertThat(second).isEqualTo(first);
        assertThat(before).isEqualTo(beforeCopy);
        assertThat(balances).isEqualTo(balancesCopy);
        assertThat(living).isEqualTo(livingCopy);
        assertThat(withdrawals).isEqualTo(withdrawalsCopy);
    }

    @Test
    void 계산할_수_없는_회차는_거절한다() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        Map<Long, Long> balances = Map.of(A, 500_000L);

        assertThatThrownBy(() -> PurchaseSimulator.simulate(input(balances, List.of(), today, today.plusDays(7),
                Map.of(), today, A, List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("회차가 없다");
        assertThatThrownBy(() -> PurchaseSimulator.simulate(input(balances, List.of(), today, today.plusDays(7),
                Map.of(), today, A, List.of(check(today, 0)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1원 이상");
        assertThatThrownBy(() -> PurchaseSimulator.simulate(input(balances, List.of(), today, today.plusDays(7),
                Map.of(), today.plusDays(2), A, List.of(check(today.plusDays(1), 10_000)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("구매일보다 이르다");
        assertThatThrownBy(() -> PurchaseSimulator.simulate(input(balances, List.of(), today, today.plusDays(7),
                Map.of(), today, null, List.of(check(today, 10_000)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("출금 계좌");
    }

    /** 계좌별 종료 잔액의 합은 통합 종료 잔액과 같다(같은 선 기준). */
    private static void assertEndBalancesAddUp(PurchaseSimulation result) {
        assertThat(result.accountComparisons().stream().mapToLong(c -> c.before().endBalance()).sum())
                .isEqualTo(result.comparison().before().endBalance());
        assertThat(result.accountComparisons().stream().mapToLong(c -> c.after().endBalance()).sum())
                .isEqualTo(result.comparison().after().endBalance());
    }

    private static SeriesPoint seriesOn(PurchaseSimulation result, LocalDate date) {
        return result.series().stream().filter(point -> point.date().equals(date)).findFirst().orElseThrow();
    }

    private static PurchaseSimulator.Input input(Map<Long, Long> balances, List<FlowEvent> before, LocalDate from,
            LocalDate to, Map<Long, Long> living, LocalDate purchaseDate, Long withdrawalAccountId,
            List<Withdrawal> withdrawals) {
        return new PurchaseSimulator.Input(balances, before, from, to, living, THRESHOLD, purchaseDate,
                withdrawalAccountId, withdrawals);
    }

    private static Withdrawal credit(int no, int months, YearMonth billingMonth, LocalDate withdrawalDate,
            long amount) {
        return new Withdrawal(no, months, billingMonth, withdrawalDate, withdrawalDate, amount);
    }

    private static Withdrawal check(LocalDate date, long amount) {
        return new Withdrawal(1, 1, null, date, date, amount);
    }

    private static FlowEvent outflow(Long accountId, LocalDate date, long amount) {
        return new FlowEvent(accountId, date, amount, FlowEvent.Type.SCHEDULED_OUTFLOW,
                FlowEvent.Confidence.CONFIRMED, "예정 지출", 1L);
    }

    private static FlowEvent income(Long accountId, LocalDate date, long amount) {
        return new FlowEvent(accountId, date, amount, FlowEvent.Type.SCHEDULED_INCOME,
                FlowEvent.Confidence.CONFIRMED, "예정 수입", 2L);
    }
}
