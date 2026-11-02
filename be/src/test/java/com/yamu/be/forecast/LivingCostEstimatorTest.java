package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.forecast.LivingCostEstimator.AccountEstimate;
import com.yamu.be.forecast.LivingCostEstimator.Confidence;
import com.yamu.be.forecast.LivingCostEstimator.DataOrigin;
import com.yamu.be.forecast.LivingCostEstimator.Estimate;
import com.yamu.be.forecast.LivingCostEstimator.MonthAmount;
import com.yamu.be.forecast.LivingCostReader.DailySpending;
import com.yamu.be.forecast.LivingCostReader.Origin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LivingCostEstimatorTest {
    private static final Long A = 3L;
    private static final Long B = 5L;
    // 10/26 기준 완료 3개월은 7·8·9월(31+31+30 = 92일)이다.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final YearMonth JUL = YearMonth.of(2026, 7);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    @Test
    void 완료_3개월의_합을_실제_일수로_나눠_일평균을_내고_달_수로_나눠_월평균을_낸다() {
        AccountEstimate result = estimateA(real(JUL, 200_000), real(AUG, 210_000), real(SEP, 220_000));

        // 630,000 ÷ 92일 = 6,847.8 → 6,848원(명세 예시의 7,000원은 월평균 ÷ 30이라 다르다)
        assertThat(result.dailyAverage()).isEqualTo(6_848L);
        assertThat(result.monthlyAverage()).isEqualTo(210_000L);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.dataOrigin()).isEqualTo(DataOrigin.REAL);
        assertThat(result.sources()).extracting(MonthAmount::month).containsExactly(JUL, AUG, SEP);
    }

    @Test
    void 쓴_달_수로_confidence가_정해지고_자료가_없으면_INSUFFICIENT다() {
        assertThat(estimateA(real(AUG, 100), real(SEP, 100)).confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(estimateA(real(SEP, 100)).confidence()).isEqualTo(Confidence.LOW);

        AccountEstimate none = estimateA();
        assertThat(none.confidence()).isEqualTo(Confidence.INSUFFICIENT);
        assertThat(none.dataOrigin()).isEqualTo(DataOrigin.INSUFFICIENT);
        assertThat(none.dailyAverage()).isNull();
        assertThat(none.monthlyAverage()).isNull();
    }

    @Test
    void 거래를_다_확인한_0원_달은_실제_0원으로_평균에_넣는다() {
        AccountEstimate result = estimateA(real(JUL, 0), real(AUG, 300_000), real(SEP, 300_000));

        // 600,000 ÷ 92일 = 6,521.7 → 6,522원. 7월을 빼면 9,836원이 된다.
        assertThat(result.dailyAverage()).isEqualTo(6_522L);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void 다_확인하지_못한_달과_행이_없는_달은_자료_누락으로_빼고_confidence가_내려간다() {
        AccountEstimate result = estimateA(incomplete(JUL, 999_999), real(SEP, 300_000));

        // 9월만 쓴다: 300,000 ÷ 30일
        assertThat(result.dailyAverage()).isEqualTo(10_000L);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.sources()).extracting(MonthAmount::month).containsExactly(SEP);

        AccountEstimate onlyIncomplete = estimateA(incomplete(JUL, 1), incomplete(AUG, 1), incomplete(SEP, 1));
        assertThat(onlyIncomplete.confidence()).isEqualTo(Confidence.INSUFFICIENT);
    }

    @Test
    void 이번_달과_완료_3개월보다_앞선_달은_평균에_넣지_않는다() {
        AccountEstimate result = estimateA(real(YearMonth.of(2026, 6), 9_000_000), real(SEP, 300_000),
                real(OCT, 9_000_000));

        assertThat(result.dailyAverage()).isEqualTo(10_000L);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void 윤년_2월은_29일로_센다() {
        LocalDate may = LocalDate.of(2028, 5, 10);
        AccountEstimate result = LivingCostEstimator.estimate(List.of(A), days(
                real(YearMonth.of(2028, 2), 90_000), real(YearMonth.of(2028, 3), 90_000),
                real(YearMonth.of(2028, 4), 90_000)), may).byAccount().get(A);

        // 270,000 ÷ (29+31+30 = 90일)
        assertThat(result.dailyAverage()).isEqualTo(3_000L);
    }

    @Test
    void 원_단위로_반올림한다() {
        assertThat(estimateA(real(SEP, 45)).dailyAverage()).isEqualTo(2L);
        assertThat(estimateA(real(SEP, 44)).dailyAverage()).isEqualTo(1L);
    }

    @Test
    void REAL_달이_하나라도_있으면_SEED_달은_섞지_않는다() {
        AccountEstimate result = estimateA(real(SEP, 300_000),
                seed(JUL, 900_000), seed(AUG, 900_000), seed(SEP, 900_000));

        assertThat(result.dataOrigin()).isEqualTo(DataOrigin.REAL);
        assertThat(result.dailyAverage()).isEqualTo(10_000L);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void REAL_자료가_없으면_SEED만으로_계산하고_출처를_SEED로_표시한다() {
        AccountEstimate result = estimateA(incomplete(SEP, 1),
                seed(JUL, 310_000), seed(AUG, 310_000), seed(SEP, 300_000));

        assertThat(result.dataOrigin()).isEqualTo(DataOrigin.SEED);
        assertThat(result.dailyAverage()).isEqualTo(10_000L);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void 이번_달_하루_평균이_평소의_1_3배를_넘어야_속도_경고다() {
        // 평소 하루 10,000원. 이번 달 26일까지 하루 13,000원이면 정확히 1.3배라 경고하지 않는다.
        AccountEstimate exact = estimateA(real(SEP, 300_000), real(OCT, 13_000L * 26));
        assertThat(exact.pace().thisMonthDaily()).isEqualTo(13_000L);
        assertThat(exact.pace().overRatio()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(exact.pace().flagged()).isFalse();

        AccountEstimate over = estimateA(real(SEP, 300_000), real(OCT, 13_001L * 26));
        assertThat(over.pace().flagged()).isTrue();
    }

    @Test
    void 이번_달을_다_확인하지_못했거나_평소_평균이_0원이면_속도를_계산하지_않는다() {
        assertThat(estimateA(real(SEP, 300_000), incomplete(OCT, 999_999)).pace()).isNull();
        assertThat(estimateA(real(SEP, 300_000)).pace()).isNull();
        assertThat(estimateA(real(SEP, 0), real(OCT, 5_000)).pace()).isNull();
    }

    @Test
    void 통합은_계좌별_합이고_REAL과_SEED_계좌를_함께_고르면_SEED로_표시한다() {
        Estimate result = LivingCostEstimator.estimate(List.of(A, B), days(
                real(A, JUL, 310_000), real(A, AUG, 310_000), real(A, SEP, 300_000),
                seed(B, SEP, 150_000)), TODAY);

        assertThat(result.byAccount().get(A).dailyAverage()).isEqualTo(10_000L);
        assertThat(result.byAccount().get(B).dailyAverage()).isEqualTo(5_000L);
        assertThat(result.total().dailyAverage()).isEqualTo(15_000L);
        assertThat(result.total().monthlyAverage()).isEqualTo(306_667L + 150_000L);
        assertThat(result.total().dataOrigin()).isEqualTo(DataOrigin.SEED);
        assertThat(result.total().confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.total().sources())
                .containsExactly(new MonthAmount(JUL, 310_000), new MonthAmount(AUG, 310_000),
                        new MonthAmount(SEP, 450_000));
    }

    @Test
    void 한_계좌라도_자료가_없으면_통합_평균을_비운다() {
        Estimate result = LivingCostEstimator.estimate(List.of(A, B), days(real(A, SEP, 300_000)), TODAY);

        assertThat(result.byAccount().get(A).dailyAverage()).isEqualTo(10_000L);
        assertThat(result.byAccount().get(B).dataOrigin()).isEqualTo(DataOrigin.INSUFFICIENT);
        assertThat(result.total().dailyAverage()).isNull();
        assertThat(result.total().dataOrigin()).isEqualTo(DataOrigin.INSUFFICIENT);
    }

    @Test
    void 선택하지_않은_계좌의_행은_무시하고_음수나_중복_행은_거절한다() {
        assertThat(LivingCostEstimator.estimate(List.of(A), days(real(B, SEP, 1)), TODAY)
                .byAccount()).containsOnlyKeys(A);
        assertThatThrownBy(() -> estimateA(real(SEP, -1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> estimateA(real(SEP, 1), real(SEP, 2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 계약_위반_오류_메시지에는_계좌_날짜_소비액을_남기지_않는다() {
        // 메시지는 전역 예외 로그에 그대로 남는다.
        assertThatThrownBy(() -> estimateA(real(SEP, -123_456)))
                .message().doesNotContain("123456").doesNotContain(SEP.toString()).doesNotContain(A.toString());
        assertThatThrownBy(() -> estimateA(real(SEP, 777_777), real(SEP, 888_888)))
                .message().doesNotContain("777777").doesNotContain("888888").doesNotContain(SEP.toString());
    }

    @Test
    void 일별_줄을_더해_월_합계를_만들고_0원_날만_있는_달도_완료로_본다() {
        List<DailySpending> july = new ArrayList<>();
        for (int day = 1; day <= 31; day++) {
            july.add(new DailySpending(A, JUL.atDay(day), 10_000, Origin.REAL, true));
        }
        AccountEstimate result = estimateA(july, real(AUG, 0), real(SEP, 0));

        // 310,000 ÷ 92일 = 3,369.6 → 3,370원
        assertThat(result.dailyAverage()).isEqualTo(3_370L);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.sources()).containsExactly(new MonthAmount(JUL, 310_000), new MonthAmount(AUG, 0),
                new MonthAmount(SEP, 0));
    }

    @Test
    void 하루라도_줄이_없거나_complete가_아닌_달은_미완료로_뺀다() {
        List<DailySpending> missingDay = real(AUG, 999_999).stream()
                .filter(row -> !row.date().equals(AUG.atDay(15))).toList();
        List<DailySpending> oneIncomplete = real(JUL, 999_999).stream()
                .map(row -> row.date().equals(JUL.atDay(31))
                        ? new DailySpending(A, row.date(), 0, Origin.REAL, false) : row)
                .toList();

        AccountEstimate result = estimateA(oneIncomplete, missingDay, real(SEP, 300_000));

        assertThat(result.dailyAverage()).isEqualTo(10_000L);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.sources()).extracting(MonthAmount::month).containsExactly(SEP);
    }

    @Test
    void 이번_달은_1일부터_오늘까지_모든_날이_있어야_속도를_계산한다() {
        // 10/26까지 26일치 줄. 오늘 줄이 빠지면 이번 달은 미완료다.
        List<DailySpending> withoutToday = real(OCT, 13_001L * 26).stream()
                .filter(row -> !row.date().equals(TODAY)).toList();

        assertThat(estimateA(real(SEP, 300_000), real(OCT, 13_001L * 26)).pace().flagged()).isTrue();
        assertThat(estimateA(real(SEP, 300_000), withoutToday).pace()).isNull();
    }

    @Test
    void 오늘보다_뒤_날짜의_줄은_거절하고_메시지에_날짜를_남기지_않는다() {
        LocalDate tomorrow = TODAY.plusDays(1);
        List<DailySpending> future = List.of(new DailySpending(A, tomorrow, 4_321, Origin.REAL, true));

        assertThatThrownBy(() -> estimateA(real(SEP, 300_000), future))
                .isInstanceOf(IllegalStateException.class)
                .message().doesNotContain(tomorrow.toString()).doesNotContain("4321");
    }

    @SafeVarargs
    private static AccountEstimate estimateA(List<DailySpending>... months) {
        return LivingCostEstimator.estimate(List.of(A), days(months), TODAY).byAccount().get(A);
    }

    @SafeVarargs
    private static List<DailySpending> days(List<DailySpending>... months) {
        return Arrays.stream(months).flatMap(List::stream).toList();
    }

    private static List<DailySpending> real(YearMonth month, long amount) {
        return real(A, month, amount);
    }

    private static List<DailySpending> real(Long accountId, YearMonth month, long amount) {
        return rows(accountId, month, amount, Origin.REAL, true);
    }

    private static List<DailySpending> seed(YearMonth month, long amount) {
        return seed(A, month, amount);
    }

    private static List<DailySpending> seed(Long accountId, YearMonth month, long amount) {
        return rows(accountId, month, amount, Origin.SEED, true);
    }

    private static List<DailySpending> incomplete(YearMonth month, long amount) {
        return rows(A, month, amount, Origin.REAL, false);
    }

    /** 그달(TODAY가 든 달은 TODAY까지) 모든 날의 줄. 금액은 첫날에 몰고 나머지 날은 0원이다. */
    private static List<DailySpending> rows(Long accountId, YearMonth month, long amount, Origin origin,
            boolean complete) {
        int lastDay = month.equals(YearMonth.from(TODAY)) ? TODAY.getDayOfMonth() : month.lengthOfMonth();
        List<DailySpending> rows = new ArrayList<>();
        for (int day = 1; day <= lastDay; day++) {
            rows.add(new DailySpending(accountId, month.atDay(day), day == 1 ? amount : 0, origin, complete));
        }
        return rows;
    }
}
