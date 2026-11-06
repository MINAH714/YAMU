package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.forecast.FlowEvent.Confidence;
import com.yamu.be.forecast.FlowEvent.Type;
import com.yamu.be.forecast.ForecastCalculator.BalancePoint;
import com.yamu.be.forecast.ForecastCalculator.Projection;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForecastCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate TO = TODAY.plusDays(10);
    private static final Long A = 3L;
    private static final Long B = 5L;
    private static final Map<Long, Long> NO_LIVING = Map.of();

    @Test
    void 날짜순으로_누적하고_같은_날_입출금은_모두_반영한_일말_잔액이다() {
        Projection result = ForecastCalculator.project(balances(A, 600_000L), List.of(
                event(A, TODAY.plusDays(4), -500_000L, Confidence.CONFIRMED),
                event(A, TODAY.plusDays(2), 1_000_000L, Confidence.CONFIRMED),
                event(A, TODAY.plusDays(4), 100_000L, Confidence.CONFIRMED)), TODAY, TO, NO_LIVING);

        assertThat(withEvents(result.byAccount().get(A)))
                .extracting(BalancePoint::date, BalancePoint::confirmed, point -> point.events().size())
                .containsExactly(tuple(TODAY.plusDays(2), 1_600_000L, 1),
                        tuple(TODAY.plusDays(4), 1_200_000L, 2));
        // 입출금이 없는 날은 직전 잔액이다.
        assertThat(at(result.byAccount().get(A), TODAY).confirmed()).isEqualTo(600_000L);
        assertThat(at(result.byAccount().get(A), TODAY.plusDays(3)).confirmed()).isEqualTo(1_600_000L);
    }

    @Test
    void 기간의_모든_날짜에_점이_있다() {
        Projection result = ForecastCalculator.project(balances(A, 600_000L, B, 100_000L), List.of(
                event(A, TODAY.plusDays(4), -500_000L, Confidence.CONFIRMED)), TODAY, TO, NO_LIVING);

        List<LocalDate> everyDay = TODAY.datesUntil(TO.plusDays(1)).toList();
        assertThat(result.total()).extracting(BalancePoint::date).containsExactlyElementsOf(everyDay);
        assertThat(result.byAccount().get(A)).extracting(BalancePoint::date).containsExactlyElementsOf(everyDay);
        assertThat(result.byAccount().get(B)).extracting(BalancePoint::date).containsExactlyElementsOf(everyDay);
    }

    @Test
    void 확정_선은_CONFIRMED만_예상_선은_EXPECTED까지_더한다() {
        Projection result = ForecastCalculator.project(balances(A, 500_000L), List.of(
                event(A, TODAY.plusDays(1), -100_000L, Confidence.CONFIRMED),
                event(A, TODAY.plusDays(3), -200_000L, Confidence.EXPECTED)), TODAY, TO, NO_LIVING);

        assertThat(List.of(at(result.total(), TODAY), at(result.total(), TODAY.plusDays(1)),
                        at(result.total(), TODAY.plusDays(3))))
                .extracting(BalancePoint::date, BalancePoint::confirmed, BalancePoint::expected)
                .containsExactly(tuple(TODAY, 500_000L, 500_000L),
                        tuple(TODAY.plusDays(1), 400_000L, 400_000L),
                        tuple(TODAY.plusDays(3), 400_000L, 200_000L));
    }

    @Test
    void 통합_선은_같은_날짜_계좌별_잔액의_합이고_입출금이_없는_계좌는_직전_잔액을_더한다() {
        // 명세 예시: A 800,000원·B 300,000원, 11/5에 A −200,000원·B −400,000원
        LocalDate nov5 = LocalDate.of(2026, 11, 5);
        Projection result = ForecastCalculator.project(balances(A, 800_000L, B, 300_000L), List.of(
                event(A, TODAY.plusDays(2), -50_000L, Confidence.CONFIRMED),
                event(A, nov5, -200_000L, Confidence.CONFIRMED),
                event(B, nov5, -400_000L, Confidence.CONFIRMED)), TODAY, TO, NO_LIVING);

        assertThat(withEvents(result.total()))
                .extracting(BalancePoint::date, BalancePoint::confirmed, point -> point.events().size())
                .containsExactly(tuple(TODAY.plusDays(2), 1_050_000L, 1), tuple(nov5, 450_000L, 2));
        assertThat(at(result.total(), TODAY).confirmed()).isEqualTo(1_100_000L);
        // 입출금이 없는 날(10/28)에도 B는 직전 잔액으로 점이 있다.
        assertThat(List.of(at(result.byAccount().get(B), TODAY.plusDays(2)), at(result.byAccount().get(B), nov5)))
                .extracting(BalancePoint::date, BalancePoint::confirmed, point -> point.events().size())
                .containsExactly(tuple(TODAY.plusDays(2), 300_000L, 0), tuple(nov5, -100_000L, 1));
        // 모든 날짜에서 같은 날짜의 계좌별 수치를 더하면 통합 수치와 같다.
        for (int i = 0; i < result.total().size(); i++) {
            BalancePoint total = result.total().get(i);
            BalancePoint a = result.byAccount().get(A).get(i);
            BalancePoint b = result.byAccount().get(B).get(i);
            assertThat(a.date()).isEqualTo(total.date());
            assertThat(b.date()).isEqualTo(total.date());
            assertThat(a.confirmed() + b.confirmed()).isEqualTo(total.confirmed());
            assertThat(a.expected() + b.expected()).isEqualTo(total.expected());
        }
    }

    @Test
    void 보수적_선은_예상_선에서_내일부터_그날까지_하루_생활비를_누적해_뺀다() {
        Projection result = ForecastCalculator.project(balances(A, 500_000L), List.of(
                        event(A, TODAY.plusDays(2), -100_000L, Confidence.EXPECTED)), TODAY, TO,
                Map.of(A, 10_000L));

        // 오늘 쓴 돈은 현재 잔액에 이미 들어 있어 오늘은 빼지 않는다.
        assertThat(List.of(at(result.total(), TODAY), at(result.total(), TODAY.plusDays(1)),
                        at(result.total(), TODAY.plusDays(2)), at(result.total(), TO)))
                .extracting(BalancePoint::expected, BalancePoint::conservative)
                .containsExactly(tuple(500_000L, 500_000L), tuple(500_000L, 490_000L),
                        tuple(400_000L, 380_000L), tuple(400_000L, 300_000L));
        // 확정 선에는 생활비 추정이 들어가지 않는다.
        assertThat(at(result.total(), TO).confirmed()).isEqualTo(500_000L);
    }

    @Test
    void 생활비_추정이_없으면_보수적_선은_null이고_한_계좌라도_없으면_통합도_null이다() {
        Projection none = ForecastCalculator.project(balances(A, 500_000L), List.of(), TODAY, TO, NO_LIVING);
        assertThat(none.total()).extracting(BalancePoint::conservative).containsOnlyNulls();

        Map<Long, Long> onlyA = new LinkedHashMap<>();
        onlyA.put(A, 10_000L);
        Projection partial = ForecastCalculator.project(balances(A, 500_000L, B, 300_000L), List.of(),
                TODAY, TO, onlyA);
        assertThat(at(partial.byAccount().get(A), TO).conservative()).isEqualTo(400_000L);
        assertThat(partial.byAccount().get(B)).extracting(BalancePoint::conservative).containsOnlyNulls();
        assertThat(partial.total()).extracting(BalancePoint::conservative).containsOnlyNulls();
    }

    @Test
    void 보수적_선도_모든_날짜에서_통합이_계좌별_합이다() {
        Projection result = ForecastCalculator.project(balances(A, 800_000L, B, 300_000L), List.of(
                        event(B, TODAY.plusDays(3), -400_000L, Confidence.CONFIRMED)), TODAY, TO,
                Map.of(A, 5_000L, B, 2_000L));

        for (int i = 0; i < result.total().size(); i++) {
            assertThat(result.byAccount().get(A).get(i).conservative() + result.byAccount().get(B).get(i).conservative())
                    .isEqualTo(result.total().get(i).conservative());
        }
    }

    @Test
    void 계좌가_하나면_통합_선과_그_계좌_선이_같다() {
        Projection result = ForecastCalculator.project(balances(A, 300_000L), List.of(
                event(A, TODAY.plusDays(5), -120_000L, Confidence.EXPECTED)), TODAY, TO, NO_LIVING);

        assertThat(result.total()).isEqualTo(result.byAccount().get(A));
    }

    @Test
    void 첫날_입출금은_첫_점에_반영하고_기간_밖_입출금은_무시한다() {
        Projection result = ForecastCalculator.project(balances(A, 100_000L), List.of(
                event(A, TODAY, -30_000L, Confidence.CONFIRMED),
                event(A, TODAY.minusDays(1), -1_000L, Confidence.CONFIRMED),
                event(A, TO, -10_000L, Confidence.CONFIRMED),
                event(A, TO.plusDays(1), -2_000L, Confidence.CONFIRMED)), TODAY, TO, NO_LIVING);

        assertThat(withEvents(result.total()))
                .extracting(BalancePoint::date, BalancePoint::confirmed)
                .containsExactly(tuple(TODAY, 70_000L), tuple(TO, 60_000L));
        assertThat(result.total()).hasSize(11);
    }

    @Test
    void 선택하지_않은_계좌의_입출금은_다른_계좌에_옮겨_담지_않고_거절한다() {
        assertThatThrownBy(() -> ForecastCalculator.project(balances(A, 100_000L), List.of(
                event(B, TODAY.plusDays(1), -10_000L, Confidence.CONFIRMED)), TODAY, TO, NO_LIVING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 금액_합이_long을_넘으면_조용히_틀린_값을_내지_않고_실패한다() {
        assertThatThrownBy(() -> ForecastCalculator.project(balances(A, Long.MAX_VALUE), List.of(
                event(A, TODAY.plusDays(1), 1L, Confidence.CONFIRMED)), TODAY, TO, NO_LIVING))
                .isInstanceOf(ArithmeticException.class);
    }

    private static Map<Long, Long> balances(Object... accountAndBalance) {
        Map<Long, Long> balances = new LinkedHashMap<>();
        for (int i = 0; i < accountAndBalance.length; i += 2) {
            balances.put((Long) accountAndBalance[i], (Long) accountAndBalance[i + 1]);
        }
        return balances;
    }

    private static BalancePoint at(List<BalancePoint> points, LocalDate date) {
        return points.stream().filter(point -> point.date().equals(date)).findFirst().orElseThrow();
    }

    private static List<BalancePoint> withEvents(List<BalancePoint> points) {
        return points.stream().filter(point -> !point.events().isEmpty()).toList();
    }

    @Test
    void CONSERVATIVE_출금은_보수적_선에만_빼고_생활비_누적은_따로_남긴다() {
        // S15P31E103-286: 하루 생활비 1,000원, 3일째 마감 전 신용카드 사용 추정 30,000원
        Projection result = ForecastCalculator.project(balances(A, 500_000L, B, 200_000L), List.of(
                event(A, TODAY.plusDays(3), -30_000L, Confidence.CONSERVATIVE)), TODAY, TO,
                Map.of(A, 1_000L, B, 500L));

        assertThat(at(result.byAccount().get(A), TODAY.plusDays(3)))
                .extracting(BalancePoint::confirmed, BalancePoint::expected, BalancePoint::conservative,
                        BalancePoint::livingCost)
                .containsExactly(500_000L, 500_000L, 467_000L, 3_000L);
        assertThat(at(result.total(), TODAY.plusDays(3)))
                .extracting(BalancePoint::expected, BalancePoint::conservative, BalancePoint::livingCost)
                .containsExactly(700_000L, 665_500L, 4_500L);
    }

    @Test
    void 생활비가_없는_계좌는_CONSERVATIVE_출금이_있어도_보수적_선이_null이다() {
        Projection result = ForecastCalculator.project(balances(A, 500_000L), List.of(
                event(A, TODAY.plusDays(3), -30_000L, Confidence.CONSERVATIVE)), TODAY, TO, NO_LIVING);

        assertThat(at(result.total(), TODAY.plusDays(3)))
                .extracting(BalancePoint::expected, BalancePoint::conservative, BalancePoint::livingCost)
                .containsExactly(500_000L, null, null);
    }

    private static FlowEvent event(Long accountId, LocalDate date, long amount, Confidence confidence) {
        Type type = amount > 0 ? Type.SCHEDULED_INCOME : Type.SCHEDULED_OUTFLOW;
        return new FlowEvent(accountId, date, amount, type, confidence, "항목", 1L);
    }
}
