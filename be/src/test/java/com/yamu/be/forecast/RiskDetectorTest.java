package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.forecast.ForecastCalculator.BalancePoint;
import com.yamu.be.forecast.RiskDetector.Assessment;
import com.yamu.be.forecast.RiskDetector.Cause;
import com.yamu.be.forecast.RiskDetector.CauseType;
import com.yamu.be.forecast.RiskDetector.Level;
import com.yamu.be.forecast.RiskDetector.Line;
import com.yamu.be.forecast.RiskDetector.RiskWindow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskDetectorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final long THRESHOLD = 100_000L;

    @Test
    void 모든_선이_기준_이상이면_SAFE이고_기준과_같은_금액도_미달이_아니다() {
        Assessment result = RiskDetector.assess(List.of(
                point(0, 300_000, 300_000, 300_000),
                point(1, 100_000, 100_000, 100_000)), THRESHOLD);

        assertThat(result.status()).isEqualTo(Level.SAFE);
        assertThat(result.windows()).isEmpty();
        assertThat(result.minBalance()).isEqualTo(100_000L);
        assertThat(result.minBalanceDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(result.endBalance()).isEqualTo(100_000L);
    }

    @Test
    void 확정_선이_기준_미만이면_DANGER이고_확정_선으로_부족액과_원인을_보여_준다() {
        FlowEvent bill = new FlowEvent(5L, TODAY.plusDays(1), -400_000L, FlowEvent.Type.CARD_BILLING,
                FlowEvent.Confidence.CONFIRMED, "카드 청구 출금", 88L);
        Assessment result = RiskDetector.assess(List.of(
                point(0, 300_000, 300_000, 300_000),
                point(1, -100_000, -100_000, -120_000, bill),
                point(2, -100_000, -100_000, -140_000)), THRESHOLD);

        assertThat(result.status()).isEqualTo(Level.DANGER);
        assertThat(result.windows()).singleElement().satisfies(window -> {
            assertThat(window.from()).isEqualTo(TODAY.plusDays(1));
            assertThat(window.to()).isEqualTo(TODAY.plusDays(2));
            assertThat(window.level()).isEqualTo(Level.DANGER);
            assertThat(window.line()).isEqualTo(Line.CONFIRMED);
            assertThat(window.lowestBalance()).isEqualTo(-100_000L);
            assertThat(window.shortfall()).isEqualTo(200_000L);
            assertThat(window.causes()).extracting(Cause::type, Cause::amount, Cause::refId)
                    .containsExactly(tuple(CauseType.CARD_BILLING, -400_000L, 88L));
        });
        // 가장 나쁜 선(보수적)으로 최저·마지막 잔액을 낸다.
        assertThat(result.minBalance()).isEqualTo(-140_000L);
        assertThat(result.endBalance()).isEqualTo(-140_000L);
    }

    @Test
    void DANGER_구간_원인에는_확정_선에_반영되지_않은_예상_출금을_넣지_않는다() {
        FlowEvent bill = new FlowEvent(5L, TODAY.plusDays(1), -400_000L, FlowEvent.Type.CARD_BILLING,
                FlowEvent.Confidence.CONFIRMED, "카드 청구 출금", 88L);
        FlowEvent accumulating = new FlowEvent(5L, TODAY.plusDays(1), -50_000L, FlowEvent.Type.CARD_BILLING,
                FlowEvent.Confidence.EXPECTED, "카드 청구 출금", 89L);
        Assessment result = RiskDetector.assess(List.of(
                point(0, 300_000, 300_000, 300_000),
                point(1, -100_000, -150_000, -150_000, bill, accumulating)), THRESHOLD);

        assertThat(result.windows()).singleElement().satisfies(window -> {
            assertThat(window.line()).isEqualTo(Line.CONFIRMED);
            assertThat(window.causes()).extracting(Cause::refId).containsExactly(88L);
        });
    }

    @Test
    void 예상_선_미달에서_생활비만_미달로_바뀌면_구간을_나누고_원인을_각_선에_맞춘다() {
        FlowEvent rent = outflow(1, -250_000);
        Assessment result = RiskDetector.assess(List.of(
                point(0, 300_000, 300_000, 300_000),
                // 1~2일: 예상 선이 미달(월세 때문)
                point(1, 300_000, 50_000, 40_000, rent),
                point(2, 300_000, 90_000, 70_000),
                // 3~4일: 예상 선은 기준 이상인데 생활비를 빼면 미달
                point(3, 300_000, 120_000, 90_000),
                point(4, 300_000, 120_000, 80_000)), THRESHOLD);

        assertThat(result.windows())
                .extracting(RiskWindow::from, RiskWindow::to, RiskWindow::level, RiskWindow::line,
                        RiskWindow::lowestBalance)
                .containsExactly(
                        tuple(TODAY.plusDays(1), TODAY.plusDays(2), Level.WARNING, Line.EXPECTED, 50_000L),
                        tuple(TODAY.plusDays(3), TODAY.plusDays(4), Level.WARNING, Line.CONSERVATIVE, 80_000L));
        // 예상 선 구간: 월세만. 생활비 원인 없음
        assertThat(result.windows().get(0).causes()).extracting(Cause::type)
                .containsExactly(CauseType.SCHEDULED_OUTFLOW);
        // 보수적 선 구간: 그 구간 최저일(4일째)의 누적 추정 생활비 40,000원
        assertThat(result.windows().get(1).causes()).extracting(Cause::type, Cause::amount, Cause::date)
                .containsExactly(tuple(CauseType.LIVING_COST_ESTIMATE, -40_000L, TODAY.plusDays(4)));
    }

    @Test
    void 예상_선만_기준_미만이면_WARNING이고_예상_선으로_본다() {
        Assessment result = RiskDetector.assess(List.of(
                point(0, 300_000, 300_000, 300_000),
                point(1, 300_000, 50_000, 40_000, outflow(1, -250_000))), THRESHOLD);

        assertThat(result.status()).isEqualTo(Level.WARNING);
        assertThat(result.windows()).singleElement().satisfies(window -> {
            assertThat(window.line()).isEqualTo(Line.EXPECTED);
            assertThat(window.lowestBalance()).isEqualTo(50_000L);
            assertThat(window.shortfall()).isEqualTo(50_000L);
            // 출금 때문에 위험해진 구간이라 추정 생활비 원인은 없다.
            assertThat(window.causes()).extracting(Cause::type).containsExactly(CauseType.SCHEDULED_OUTFLOW);
        });
    }

    @Test
    void 생활비_추정_때문에만_위험해지면_보수적_선으로_보고_LIVING_COST_ESTIMATE를_원인에_넣는다() {
        Assessment result = RiskDetector.assess(List.of(
                point(0, 150_000, 150_000, 150_000),
                point(1, 150_000, 150_000, 120_000),
                point(2, 150_000, 150_000, 90_000),
                point(3, 150_000, 150_000, 60_000)), THRESHOLD);

        assertThat(result.status()).isEqualTo(Level.WARNING);
        assertThat(result.windows()).singleElement().satisfies(window -> {
            assertThat(window.from()).isEqualTo(TODAY.plusDays(2));
            assertThat(window.line()).isEqualTo(Line.CONSERVATIVE);
            assertThat(window.lowestBalance()).isEqualTo(60_000L);
            // 최저 날(3일째)까지 누적한 추정 생활비 90,000원
            assertThat(window.causes()).extracting(Cause::type, Cause::amount, Cause::date)
                    .containsExactly(tuple(CauseType.LIVING_COST_ESTIMATE, -90_000L, TODAY.plusDays(3)));
        });
    }

    @Test
    void WARNING과_DANGER가_바뀌면_구간을_나눈다() {
        Assessment result = RiskDetector.assess(List.of(
                point(0, 200_000, 200_000, 200_000),
                point(1, 200_000, 90_000, 90_000),
                point(2, 200_000, 80_000, 80_000),
                point(3, 50_000, 50_000, 50_000),
                point(4, 200_000, 70_000, 70_000),
                point(5, 200_000, 200_000, 200_000)), THRESHOLD);

        assertThat(result.status()).isEqualTo(Level.DANGER);
        assertThat(result.windows())
                .extracting(RiskWindow::from, RiskWindow::to, RiskWindow::level)
                .containsExactly(tuple(TODAY.plusDays(1), TODAY.plusDays(2), Level.WARNING),
                        tuple(TODAY.plusDays(3), TODAY.plusDays(3), Level.DANGER),
                        tuple(TODAY.plusDays(4), TODAY.plusDays(4), Level.WARNING));
    }

    @Test
    void 보수적_선이_없으면_확정_예상_선으로만_판단하고_예상_선으로_최저를_낸다() {
        Assessment result = RiskDetector.assess(List.of(
                point(0, 150_000, 150_000, null),
                point(1, 150_000, 120_000, null)), THRESHOLD);

        assertThat(result.status()).isEqualTo(Level.SAFE);
        assertThat(result.minBalance()).isEqualTo(120_000L);
    }

    @Test
    void 최저_잔액이_같으면_이른_날을_돌려준다() {
        Assessment result = RiskDetector.assess(List.of(
                point(0, 150_000, 150_000, 150_000),
                point(1, 120_000, 120_000, 120_000),
                point(2, 120_000, 120_000, 120_000)), THRESHOLD);

        assertThat(result.minBalanceDate()).isEqualTo(TODAY.plusDays(1));
    }

    private static FlowEvent outflow(int day, long amount) {
        return new FlowEvent(3L, TODAY.plusDays(day), amount, FlowEvent.Type.SCHEDULED_OUTFLOW,
                FlowEvent.Confidence.EXPECTED, "월세", 1L);
    }

    private static BalancePoint point(int day, long confirmed, long expected, Long conservative, FlowEvent... events) {
        return new BalancePoint(TODAY.plusDays(day), confirmed, expected, conservative, new ArrayList<>(List.of(events)));
    }

    private static BalancePoint point(int day, long confirmed, long expected, long conservative, FlowEvent... events) {
        return point(day, confirmed, expected, Long.valueOf(conservative), events);
    }
}
