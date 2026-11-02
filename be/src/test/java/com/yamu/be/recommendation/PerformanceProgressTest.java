package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.yamu.be.recommendation.RecommendationInput.PerformanceProgress;
import java.time.YearMonth;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PerformanceProgressTest {
    private static final YearMonth PERIOD = YearMonth.of(2026, 10);

    @ParameterizedTest
    @CsvSource({"290000,300000,10000,true", "280000,300000,10000,false",
            "300000,300000,10000,false", "0,300000,300000,true", "0,0,0,false",
            "9223372036854775806,9223372036854775807,2,true"})
    void preservesMinimumCrossingBoundariesAndAvoidsOverflow(long before, long minimum, long recognized, boolean expected) {
        var progress = new PerformanceProgress(PERIOD, before, minimum, recognized);
        assertThat(progress.isKnown()).isTrue();
        assertThat(progress.newlyMeetsMinimum()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0", "0,-1,0", "0,0,-1"})
    void rejectsNegativeAmounts(long before, long minimum, long recognized) {
        assertThatThrownBy(() -> new PerformanceProgress(PERIOD, before, minimum, recognized))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("unknownProgress")
    void missingPeriodOrAmountIsUnknown(PerformanceProgress progress) {
        assertThat(progress.isKnown()).isFalse();
        assertThatThrownBy(progress::newlyMeetsMinimum).isInstanceOf(IllegalStateException.class);
    }

    static Stream<PerformanceProgress> unknownProgress() {
        return Stream.of(new PerformanceProgress(null, 0L, 300_000L, 10_000L),
                new PerformanceProgress(PERIOD, null, 300_000L, 10_000L),
                new PerformanceProgress(PERIOD, 0L, null, 10_000L),
                new PerformanceProgress(PERIOD, 0L, 300_000L, null));
    }
}
