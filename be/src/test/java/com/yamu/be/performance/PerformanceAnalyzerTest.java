package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.performance.PerformanceAnalyzer.Input;
import com.yamu.be.performance.PerformanceAnalyzer.Likelihood;
import com.yamu.be.performance.PerformanceAnalyzer.MonthSample;
import com.yamu.be.performance.PerformanceAnalyzer.Movable;
import com.yamu.be.performance.PerformanceAnalyzer.Result;
import com.yamu.be.performance.PerformanceAnalyzer.Scheduled;
import com.yamu.be.performance.PerformanceAnalyzer.Target;
import com.yamu.be.performance.PerformanceAnalyzer.Tier;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 유불리 판정 경계(S15P31E103-375). 오늘은 11월 10일(30일까지라 자연 소비는 11~30일 20일). */
class PerformanceAnalyzerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    /** 30만원 구간 원화 월 한도 1만원, 그 아래 10만원 구간 3천원. */
    static final Target TARGET = new Target(300_000, List.of(
            new Tier(100_000, 3_000L, List.of("편의점 5% 할인")),
            new Tier(300_000, 10_000L, List.of("생활 10% 할인"))));

    @Test
    void 인정_실적이_목표와_같으면_이미_달성이고_자연_소비를_보지_않는다() {
        Result r = analyze(TARGET, 300_000, List.of(), List.of(), List.of(), Set.of());

        assertThat(r.guide()).isEqualTo(PerformanceGuide.ALREADY_ACHIEVED);
        assertThat(r.steps()).isEmpty();
        assertThat(r.remaining()).isZero();
        assertThat(r.benefitsAtStake()).containsExactly("생활 10% 할인");
    }

    @Test
    void 예상이_목표와_같으면_평소대로이고_1원_모자라면_아니다() {
        // 20만 + 예정 3만 + 자연 소비 평균 7만 = 30만
        Result keep = analyze(TARGET, 200_000, months(60_000, 80_000), List.of(scheduled(1, 30_000)), List.of(),
                Set.of());
        assertThat(keep.guide()).isEqualTo(PerformanceGuide.KEEP_NATURAL);
        assertThat(keep.projected()).isEqualTo(300_000);
        assertThat(keep.natural()).isEqualTo(70_000);
        assertThat(keep.averageDaily()).isEqualTo(3_500);
        assertThat(keep.shortfall()).isZero();
        assertThat(keep.likelihood()).isEqualTo(Likelihood.LIKELY);

        Result short1 = analyze(TARGET, 199_999, months(60_000, 80_000), List.of(scheduled(1, 30_000)), List.of(),
                Set.of());
        assertThat(short1.guide()).isNotEqualTo(PerformanceGuide.KEEP_NATURAL);
        assertThat(short1.shortfallIfTrend()).isEqualTo(1);
    }

    @Test
    void 옮길_수_있는_예정_지출로_다_채우면_추가_지출_없이_달성_추천이다() {
        // 20만 + 자연 5만 = 25만, 5만 모자람. 옮길 수 있는 3만·2만으로 딱 채움. 옮길 수 없는 10만은 세지 않는다
        List<Movable> movable = List.of(
                movable(9, "월세", 100_000, false),
                movable(7, "통신비", 30_000, true),
                movable(8, "보험", 20_000, true));
        Result r = analyze(TARGET, 200_000, months(50_000), List.of(), movable, Set.of());

        assertThat(r.guide()).isEqualTo(PerformanceGuide.ACHIEVE_RECOMMENDED);
        assertThat(r.eligibleMovableTotal()).isEqualTo(50_000);
        assertThat(r.shortfall()).isZero();
        assertThat(r.lossIfForced()).isZero();
        assertThat(r.headline()).isEqualTo("예정 지출 2건 결제 카드만 바꾸면 추가 지출 없이 채워요");
        assertThat(r.steps()).extracting(PerformanceAnalyzer.Step::label).containsExactly("남은 실적",
                "월말까지 자연 소비 예측", "이 카드로 낼 예정 지출", "예정 지출을 옮기면 채울 수 있는 돈", "그래도 모자라는 돈",
                "달성하면 더 받는 혜택", "판단");
    }

    @Test
    void 하나만_옮겨도_채우면_그_이름으로_안내한다() {
        Result r = analyze(TARGET, 200_000, months(50_000), List.of(),
                List.of(movable(7, "통신비", 60_000, true), movable(8, "보험", 20_000, true)), Set.of());

        assertThat(r.headline()).isEqualTo("통신비 결제 카드만 바꾸면 추가 지출 없이 채워요");
        assertThat(r.message()).startsWith("11월 18일까지 통신비 결제처에서도");
    }

    @Test
    void 모자라는_돈이_더_받는_혜택과_같으면_달성_추천이고_1원_크면_비추천이다() {
        // 목표 구간 1만원 − 예상(29만)이 닿는 10만원 구간 3천원 = 7천원 더 받음
        Result equal = analyze(TARGET, 283_000, months(0), List.of(), List.of(), Set.of());
        assertThat(equal.extraBenefit()).isEqualTo(7_000);
        assertThat(equal.shortfall()).isEqualTo(17_000);
        assertThat(equal.guide()).isEqualTo(PerformanceGuide.EXTRA_SPENDING_NOT_RECOMMENDED);

        Result achieve = analyze(TARGET, 293_000, months(0), List.of(), List.of(), Set.of());
        assertThat(achieve.shortfall()).isEqualTo(7_000);
        assertThat(achieve.guide()).isEqualTo(PerformanceGuide.ACHIEVE_RECOMMENDED);
        assertThat(achieve.lossIfForced()).isZero();
        assertThat(achieve.headline()).isEqualTo("7,000원 더 쓰면 혜택을 7,000원 더 받아요");

        Result loss = analyze(TARGET, 292_999, months(0), List.of(), List.of(), Set.of());
        assertThat(loss.guide()).isEqualTo(PerformanceGuide.EXTRA_SPENDING_NOT_RECOMMENDED);
        assertThat(loss.lossIfForced()).isEqualTo(1);
    }

    @Test
    void 예상이_아래_구간에도_못_닿으면_목표_구간_한도_전부가_더_받는_혜택이다() {
        Result r = analyze(TARGET, 50_000, months(10_000), List.of(), List.of(), Set.of());

        assertThat(r.extraBenefit()).isEqualTo(10_000);
        assertThat(r.guide()).isEqualTo(PerformanceGuide.EXTRA_SPENDING_NOT_RECOMMENDED);
        assertThat(r.lossIfForced()).isEqualTo(240_000 - 10_000);
        assertThat(r.likelihood()).isEqualTo(Likelihood.UNLIKELY);
    }

    @Test
    void 원화_한도가_없는_구간이면_혜택을_셀_수_없어_추가_소비를_권하지_않는다() {
        Target points = new Target(300_000, List.of(new Tier(300_000, null, List.of("포인트 적립"))));
        Result r = analyze(points, 299_000, months(0), List.of(), List.of(), Set.of());

        assertThat(r.extraBenefit()).isNull();
        assertThat(r.guide()).isEqualTo(PerformanceGuide.EXTRA_SPENDING_NOT_RECOMMENDED);
        assertThat(r.lossIfForced()).isNull();
        assertThat(r.steps().get(5).note()).isEqualTo("원화 한도가 없거나 알 수 없어 금액으로 셀 수 없어요");

        // 옮기기로 채우면 혜택을 셀 수 없어도 추가 지출이 없으니 달성 추천
        Result move = analyze(points, 299_000, months(0), List.of(), List.of(movable(7, "통신비", 1_000, true)),
                Set.of());
        assertThat(move.guide()).isEqualTo(PerformanceGuide.ACHIEVE_RECOMMENDED);
    }

    @Test
    void 예상이_닿는_낮은_구간의_한도를_모르면_더_받는_혜택을_셀_수_없다() {
        // 10만원 구간 한도를 모름(겹친 한도 등, 373이 null로 비움). 0원으로 보면 1만원 전부를 더 받는다고 잘못 센다
        Target unknownLower = new Target(300_000, List.of(
                new Tier(100_000, null, List.of("편의점 5% 할인")),
                new Tier(300_000, 10_000L, List.of("생활 10% 할인"))));
        Result r = analyze(unknownLower, 295_000, months(0), List.of(), List.of(), Set.of());

        assertThat(r.extraBenefit()).isNull();
        assertThat(r.guide()).isEqualTo(PerformanceGuide.EXTRA_SPENDING_NOT_RECOMMENDED);
        assertThat(r.lossIfForced()).isNull();

        // 예상이 그 구간에 닿지 않으면 뺄 것이 없어 목표 구간 한도 전부
        Result below = analyze(unknownLower, 50_000, months(0), List.of(), List.of(), Set.of());
        assertThat(below.extraBenefit()).isEqualTo(10_000);
    }

    @Test
    void 가정해_옮긴_예정_지출은_예상에_더하고_옮길_목록에서_뺀다() {
        List<Movable> movable = List.of(movable(7, "통신비", 60_000, true), movable(8, "보험", 20_000, true));
        Result r = analyze(TARGET, 200_000, months(50_000), List.of(), movable, Set.of(7L));

        assertThat(r.scenarioMovedTotal()).isEqualTo(60_000);
        assertThat(r.projected()).isEqualTo(310_000);
        assertThat(r.guide()).isEqualTo(PerformanceGuide.KEEP_NATURAL);
        assertThat(r.movable()).extracting(Movable::cashflowItemId).containsExactly(8L);
        assertThat(r.scenarioMoved()).extracting(Movable::cashflowItemId).containsExactly(7L);
        assertThat(r.steps().get(2).amount()).isEqualTo(60_000);
        assertThat(r.steps().get(2).note()).isEqualTo("잔고예보 예정 지출 0건 + 옮긴다고 가정한 1건");
    }

    @Test
    void 말일이면_자연_소비는_0원이다() {
        LocalDate lastDay = LocalDate.of(2026, 11, 30);
        Result r = PerformanceAnalyzer.analyze(new Input(lastDay, TARGET, 250_000, months(90_000), List.of(),
                List.of(), Set.of()));

        assertThat(r.natural()).isZero();
        assertThat(r.averageDaily()).isZero();
        assertThat(r.steps().get(1).note()).isEqualTo("오늘이 말일이라 남은 날이 없어요");
    }

    @Test
    void 예상이_목표의_90퍼센트_이상이면_BORDERLINE이다() {
        assertThat(PerformanceAnalyzer.likelihood(270_000, 300_000)).isEqualTo(Likelihood.BORDERLINE);
        assertThat(PerformanceAnalyzer.likelihood(269_999, 300_000)).isEqualTo(Likelihood.UNLIKELY);
        assertThat(PerformanceAnalyzer.likelihood(300_000, 300_000)).isEqualTo(Likelihood.LIKELY);
    }

    @Test
    void 자연_소비_근거에_달마다_금액을_적는다() {
        Result r = analyze(TARGET, 100_000, List.of(new MonthSample(YearMonth.of(2026, 9), 46_000),
                new MonthSample(YearMonth.of(2026, 10), 55_000)), List.of(), List.of(), Set.of());

        assertThat(r.natural()).isEqualTo(50_500);
        assertThat(r.steps().get(1).note()).isEqualTo("최근 2개월 같은 기간(11일~말일) 평균 · 9월 46,000원 · 10월 55,000원");
    }

    @Test
    void 구간_이름은_만원_단위다() {
        assertThat(PerformanceAnalyzer.label(300_000)).isEqualTo("30만원 이상");
        assertThat(PerformanceAnalyzer.label(15_000)).isEqualTo("1만 5,000원 이상");
        assertThat(PerformanceAnalyzer.label(5_000)).isEqualTo("5,000원 이상");
    }

    private static Result analyze(Target target, long recognized, List<MonthSample> history,
            List<Scheduled> scheduled, List<Movable> movable, Set<Long> scenario) {
        return PerformanceAnalyzer.analyze(new Input(TODAY, target, recognized, history, scheduled, movable,
                scenario));
    }

    private static List<MonthSample> months(long... amounts) {
        List<MonthSample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            samples.add(new MonthSample(YearMonth.of(2026, 8 + i), amounts[i]));
        }
        return samples;
    }

    private static Scheduled scheduled(long id, long amount) {
        return new Scheduled(id, "구독", TODAY.plusDays(5), amount);
    }

    private static Movable movable(long id, String title, long amount, boolean eligible) {
        LocalDate date = TODAY.plusDays(10);
        return new Movable(id, title, date, amount, "ACCOUNT", eligible, date.minusDays(2), null,
                eligible ? null : "카드로 낼 수 없는 지출이에요");
    }
}
