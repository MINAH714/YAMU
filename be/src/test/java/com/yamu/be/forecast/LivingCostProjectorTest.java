package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.forecast.LivingCostEstimator.Confidence;
import com.yamu.be.forecast.LivingCostEstimator.DataOrigin;
import com.yamu.be.forecast.LivingCostReader.DailySpending;
import com.yamu.be.forecast.LivingCostReader.Origin;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LivingCostProjectorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final long A = 1L;
    private static final long B = 2L;

    @Test
    void AI_서버가_없으면_TSB로_내일부터_N일_생활비를_낸다() {
        // 매일 5,000원이면 TSB 하루 예측은 5,000원. 31일 155,000원 − 오늘 쓴 5,000원 = 150,000원 ÷ 30 = 하루 5,000원
        LivingCostProjector.Projection result = new LivingCostProjector(AiLivingCostClient.disabled())
                .project(List.of(A), everyDay(A, TODAY.minusDays(120), TODAY, 5_000, Origin.REAL), TODAY, 30);

        assertThat(result.byAccount().get(A)).isEqualTo(new LivingCostProjector.AccountProjection(A, 150_000L,
                5_000L, "TSB", Confidence.HIGH, DataOrigin.REAL));
        assertThat(result.total()).isEqualTo(new LivingCostProjector.Total(150_000L, 5_000L, "TSB", Confidence.HIGH,
                DataOrigin.REAL));
    }

    @Test
    void AI는_어제까지_90일을_보내고_N_더하기_1일을_직선으로_이은_뒤_오늘_쓴_금액을_뺀다() {
        FakeAi ai = new FakeAi(Map.of(14, 100_000L, 30, 200_000L));
        List<DailySpending> rows = everyDay(A, TODAY.minusDays(120), TODAY, 5_000, Origin.REAL);

        LivingCostProjector.AccountProjection result = new LivingCostProjector(ai)
                .project(List.of(A), rows, TODAY, 30).byAccount().get(A);

        // 31일 = 30일 하루 평균 × 31 = 206,667원. 오늘 5,000원을 빼 201,667원 ÷ 30 = 6,722원(반올림)
        assertThat(result.method()).isEqualTo("ML_V1");
        assertThat(result.dailyAmount()).isEqualTo(6_722L);
        assertThat(result.projectedAmount()).isEqualTo(6_722L * 30);
        assertThat(ai.calls).extracting(Call::horizon).containsExactly(14, 30);
        assertThat(ai.calls).allSatisfy(call -> {
            assertThat(call.asOf()).isEqualTo(YESTERDAY);
            assertThat(call.histories()).containsOnlyKeys("a0");
            assertThat(call.histories().get("a0")).hasSize(90).containsOnly(5_000L);
        });
    }

    @Test
    void 예보_7일이면_8일_값이_필요해_AI_14일만_부른다() {
        FakeAi ai = new FakeAi(Map.of(14, 100_000L));

        LivingCostProjector.AccountProjection result = new LivingCostProjector(ai)
                .project(List.of(A), everyDay(A, TODAY.minusDays(120), TODAY, 5_000, Origin.REAL), TODAY, 7)
                .byAccount().get(A);

        // TSB 7일 35,000원 → AI 14일 100,000원 사이 8일 = 35,000 + 65,000 × 1/7 = 44,286원. − 5,000원 ÷ 7 = 5,612원
        assertThat(ai.calls).extracting(Call::horizon).containsExactly(14);
        assertThat(result.method()).isEqualTo("ML_V1");
        assertThat(result.dailyAmount()).isEqualTo(5_612L);
    }

    @Test
    void 예보_6일_이하는_7일_이하만_필요해_AI를_부르지_않는다() {
        FakeAi ai = new FakeAi(Map.of(14, 100_000L, 30, 200_000L));

        LivingCostProjector.AccountProjection result = new LivingCostProjector(ai)
                .project(List.of(A), everyDay(A, TODAY.minusDays(120), TODAY, 5_000, Origin.REAL), TODAY, 6)
                .byAccount().get(A);

        assertThat(ai.calls).isEmpty();
        assertThat(result.method()).isEqualTo("TSB");
        assertThat(result.projectedAmount()).isEqualTo(30_000L);
    }

    @Test
    void 기록_13일은_TSB_14일부터_AI이고_신뢰도는_기록_길이다() {
        FakeAi ai = new FakeAi(Map.of(14, 70_000L, 30, 150_000L));
        // A: 어제까지 13일 기록, B: 14일 기록
        List<DailySpending> rows = Stream.concat(
                everyDay(A, YESTERDAY.minusDays(12), TODAY, 5_000, Origin.REAL).stream(),
                everyDay(B, YESTERDAY.minusDays(13), TODAY, 5_000, Origin.REAL).stream()).toList();

        LivingCostProjector.Projection result = new LivingCostProjector(ai).project(List.of(A, B), rows, TODAY, 30);

        assertThat(result.byAccount().get(A).method()).isEqualTo("TSB");
        assertThat(result.byAccount().get(A).confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.byAccount().get(B).method()).isEqualTo("ML_V1");
        assertThat(result.byAccount().get(B).confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.total().method()).isEqualTo("MIXED");
        assertThat(result.total().confidence()).isEqualTo(Confidence.LOW);
        // AI에는 기록 14일 이상인 계좌만 보낸다
        assertThat(ai.calls).allSatisfy(call -> assertThat(call.histories()).hasSize(1));
    }

    @Test
    void 기록_60일부터_HIGH다() {
        LivingCostProjector projector = new LivingCostProjector(AiLivingCostClient.disabled());

        assertThat(projector.project(List.of(A), everyDay(A, YESTERDAY.minusDays(58), TODAY, 1_000, Origin.REAL),
                TODAY, 30).byAccount().get(A).confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(projector.project(List.of(A), everyDay(A, YESTERDAY.minusDays(59), TODAY, 1_000, Origin.REAL),
                TODAY, 30).byAccount().get(A).confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void AI가_한_번이라도_실패하면_그_요청의_모든_계좌를_TSB로_계산한다() {
        // 14일은 답하고 30일은 실패
        FakeAi ai = new FakeAi(Map.of(14, 100_000L));
        List<DailySpending> rows = Stream.concat(
                everyDay(A, TODAY.minusDays(120), TODAY, 5_000, Origin.REAL).stream(),
                everyDay(B, TODAY.minusDays(120), TODAY, 2_000, Origin.REAL).stream()).toList();

        LivingCostProjector.Projection result = new LivingCostProjector(ai).project(List.of(A, B), rows, TODAY, 30);

        assertThat(result.byAccount().values()).extracting(LivingCostProjector.AccountProjection::method)
                .containsOnly("TSB");
        assertThat(result.total().projectedAmount()).isEqualTo(150_000L + 60_000L);
    }

    @Test
    void SEED와_REAL을_이어_붙이고_기록_뒤_빈_날은_0원이다() {
        FakeAi ai = new FakeAi(Map.of(14, 50_000L, 30, 90_000L));
        // 40일 전~11일 전 SEED, 10일 전은 줄 없음(카드 등록일), 9일 전~오늘 REAL
        List<DailySpending> rows = new ArrayList<>(everyDay(A, TODAY.minusDays(40), TODAY.minusDays(11), 3_000,
                Origin.SEED));
        rows.addAll(everyDay(A, TODAY.minusDays(9), TODAY, 4_000, Origin.REAL));

        LivingCostProjector.AccountProjection result = new LivingCostProjector(ai)
                .project(List.of(A), rows, TODAY, 30).byAccount().get(A);

        long[] sent = ai.calls.get(0).histories().get("a0");
        assertThat(sent[89 - 39]).isEqualTo(3_000L);   // 40일 전(기록 시작)
        assertThat(sent[89 - 9]).isZero();             // 10일 전: 줄 없음
        assertThat(sent[89]).isEqualTo(4_000L);        // 어제
        assertThat(sent[0]).isZero();                  // 기록 시작 전
        assertThat(result.dataOrigin()).isEqualTo(DataOrigin.SEED);
        assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM); // 40일
    }

    @Test
    void 다_확인하지_못한_날은_확인된_금액을_쓴다() {
        List<DailySpending> rows = new ArrayList<>(everyDay(A, TODAY.minusDays(30), TODAY.minusDays(2), 1_000,
                Origin.REAL));
        rows.add(new DailySpending(A, YESTERDAY, 7_000, 7_000, Origin.REAL, false));
        FakeAi ai = new FakeAi(Map.of(14, 1L, 30, 1L));

        new LivingCostProjector(ai).project(List.of(A), rows, TODAY, 30);

        assertThat(ai.calls.get(0).histories().get("a0")[89]).isEqualTo(7_000L);
    }

    @Test
    void 어제까지_기록이_없으면_예상_생활비를_비우고_통합도_비운다() {
        // A는 오늘 첫 결제, B는 기록 충분
        List<DailySpending> rows = Stream.concat(
                Stream.of(new DailySpending(A, TODAY, 9_000, 9_000, Origin.REAL, true)),
                everyDay(B, TODAY.minusDays(120), TODAY, 2_000, Origin.REAL).stream()).toList();

        LivingCostProjector.Projection result = new LivingCostProjector(AiLivingCostClient.disabled())
                .project(List.of(A, B), rows, TODAY, 30);

        assertThat(result.byAccount().get(A))
                .isEqualTo(LivingCostProjector.AccountProjection.insufficient(A));
        assertThat(result.byAccount().get(B).projectedAmount()).isEqualTo(60_000L);
        assertThat(result.total()).isEqualTo(new LivingCostProjector.Total(null, null, "TSB",
                Confidence.INSUFFICIENT, DataOrigin.INSUFFICIENT));
    }

    @Test
    void 오늘_이미_예측보다_많이_썼으면_0원이고_기간이_0일이면_0원이다() {
        List<DailySpending> rows = new ArrayList<>(everyDay(A, TODAY.minusDays(120), YESTERDAY, 1_000, Origin.REAL));
        rows.add(new DailySpending(A, TODAY, 500_000, 500_000, Origin.REAL, true));
        LivingCostProjector projector = new LivingCostProjector(AiLivingCostClient.disabled());

        assertThat(projector.project(List.of(A), rows, TODAY, 7).byAccount().get(A).projectedAmount()).isZero();
        assertThat(projector.project(List.of(A), rows, TODAY, 0).byAccount().get(A).projectedAmount()).isZero();
    }

    @Test
    void 직선_잇기는_문서_예시와_같고_앞_점보다_낮은_점은_올린다() {
        // docs/be/forecast.md: 14일 313,937원, 30일 560,373원이면 15일은 329,339원
        assertThat(LivingCostProjector.interpolate(150_000, 313_937, 560_373, 15)).isEqualTo(329_339L);
        assertThat(LivingCostProjector.interpolate(70_000, 140_000, 300_000, 7)).isEqualTo(70_000L);
        assertThat(LivingCostProjector.interpolate(70_000, 140_000, 300_000, 14)).isEqualTo(140_000L);
        assertThat(LivingCostProjector.interpolate(70_000, 140_000, 300_000, 30)).isEqualTo(300_000L);
        assertThat(LivingCostProjector.interpolate(70_000, 140_000, 300_000, 31)).isEqualTo(310_000L);
        assertThat(LivingCostProjector.interpolate(70_000, 140_000, 300_000, 3)).isEqualTo(30_000L);
        // AI 14일이 TSB 7일보다 작으면 14일을 7일 높이로, 30일이 14일보다 작으면 30일을 14일 높이로
        assertThat(LivingCostProjector.interpolate(70_000, 50_000, 300_000, 10)).isEqualTo(70_000L);
        assertThat(LivingCostProjector.interpolate(70_000, 140_000, 100_000, 20)).isEqualTo(140_000L);
        assertThatThrownBy(() -> LivingCostProjector.interpolate(70_000, 140_000, -1, 15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<DailySpending> everyDay(long accountId, LocalDate from, LocalDate to, long amount,
            Origin origin) {
        return from.datesUntil(to.plusDays(1))
                .map(date -> new DailySpending(accountId, date, amount, amount, origin, true)).toList();
    }

    private record Call(LocalDate asOf, int horizon, Map<String, long[]> histories) {}

    /** 정한 예측 일수에만 같은 금액으로 답하는 AI. 없는 일수는 실패(empty). */
    private static final class FakeAi extends AiLivingCostClient {
        private final Map<Integer, Long> answers;
        private final List<Call> calls = new ArrayList<>();

        FakeAi(Map<Integer, Long> answers) {
            super(null, Clock.systemUTC(), Duration.ZERO);
            this.answers = answers;
        }

        @Override
        boolean enabled() {
            return true;
        }

        @Override
        Optional<Map<String, Long>> predict(LocalDate asOf, int horizonDays, Map<String, long[]> histories) {
            calls.add(new Call(asOf, horizonDays, Map.copyOf(histories)));
            Long answer = answers.get(horizonDays);
            if (answer == null) {
                return Optional.empty();
            }
            Map<String, Long> out = new HashMap<>();
            histories.keySet().forEach(key -> out.put(key, answer));
            return Optional.of(out);
        }
    }
}
