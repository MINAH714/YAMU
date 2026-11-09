package com.yamu.be.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.forecast.BalanceForecast;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 홈의 작은 규칙(위험 시작일, 동기화 결과 저장 형식)을 DB 없이 확인한다. */
class HomeRulesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 10);

    @Test
    void 위험_시작일은_세_선_중_하나라도_안전_잔액_밑으로_처음_내려간_날이다() {
        List<BalanceForecast.SeriesPoint> series = List.of(
                point(D1, 200_000, 200_000, 150_000L),
                point(D1.plusDays(1), 200_000, 200_000, 99_999L),
                point(D1.plusDays(2), 50_000, 50_000, 0L));

        assertThat(HomeService.firstRiskDate(series, 100_000)).isEqualTo(D1.plusDays(1));
    }

    @Test
    void 보수적_선이_없으면_확정_예상_선만_본다() {
        List<BalanceForecast.SeriesPoint> series = List.of(
                point(D1, 200_000, 200_000, null),
                point(D1.plusDays(1), 200_000, 100_000, null));

        assertThat(HomeService.firstRiskDate(series, 100_000)).isNull();
    }

    @Test
    void 동기화_결과는_저장했다_꺼내도_같다() {
        SyncResponse withFailures = new SyncResponse(LocalDateTime.of(2026, 11, 10, 3, 4, 5), 0,
                List.of(new SyncResponse.Failure(7L, "FIN_API_UNAVAILABLE"), new SyncResponse.Failure(9L, "UPSTREAM_TIMEOUT")));
        SyncResponse clean = new SyncResponse(LocalDateTime.of(2026, 11, 10, 3, 4, 5), 0, List.of());

        assertThat(SyncService.decode(SyncService.encode(withFailures))).contains(withFailures);
        assertThat(SyncService.decode(SyncService.encode(clean))).contains(clean);
        assertThat(SyncService.decode("깨진 값")).isEmpty();
    }

    private static BalanceForecast.SeriesPoint point(LocalDate date, long confirmed, long expected, Long conservative) {
        return new BalanceForecast.SeriesPoint(date, confirmed, expected, conservative, List.of());
    }
}
