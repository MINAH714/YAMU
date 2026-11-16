package com.yamu.be.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.forecast.BalanceForecast;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 홈의 작은 규칙(위험 시작일, 할 일 순서, 동기화 결과 저장 형식)을 DB 없이 확인한다. */
class HomeRulesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 10);

    @Test
    void 위험_시작일은_예상_보수적_두_선_중_하나라도_안전_잔액_밑으로_처음_내려간_날이다() {
        List<BalanceForecast.SeriesPoint> series = List.of(
                point(D1, 200_000, 200_000, 150_000L),
                point(D1.plusDays(1), 200_000, 200_000, 99_999L),
                point(D1.plusDays(2), 50_000, 50_000, 0L));

        assertThat(HomeService.firstRiskDate(series, 100_000)).isEqualTo(D1.plusDays(1));
    }

    @Test
    void 확정_선만_안전_잔액_밑이면_위험_날이_아니다() {
        // 월급이 예상 항목이라 확정 선만 낮은 날. 잔고예보(S15P31E103-356)는 SAFE다(운영 앱 점검 B-05).
        List<BalanceForecast.SeriesPoint> series = List.of(
                point(D1, 50_000, 200_000, 150_000L),
                point(D1.plusDays(1), 50_000, 200_000, null));

        assertThat(HomeService.firstRiskDate(series, 100_000)).isNull();
    }

    @Test
    void 보수적_선이_없으면_예상_선만_본다() {
        List<BalanceForecast.SeriesPoint> series = List.of(
                point(D1, 0, 200_000, null),
                point(D1.plusDays(1), 0, 100_000, null),
                point(D1.plusDays(2), 0, 99_999, null));

        assertThat(HomeService.firstRiskDate(series, 100_000)).isEqualTo(D1.plusDays(2));
    }

    @Test
    void 할_일은_실적_부족_순서이고_두_개까지다() {
        List<HomeResponse.PerformanceAlert> alerts = List.of(alert(2L, "딥드림", 180_000L, 11),
                alert(3L, "탄탄대로", 50_000L, 11), alert(4L, "노리2", 10_000L, 0));

        assertThat(HomeService.todos(alerts)).containsExactly(
                new HomeResponse.Todo("딥드림 실적까지 180,000원 남았어요", "마감 D-11", "WARNING", 2L),
                new HomeResponse.Todo("탄탄대로 실적까지 50,000원 남았어요", "마감 D-11", "WARNING", 3L));
    }

    @Test
    void 실적_영역이_실패하면_할_일은_비어_있다() {
        assertThat(HomeService.todos(null)).isEmpty();
        assertThat(HomeService.todos(List.of())).isEmpty();
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

    private static HomeResponse.PerformanceAlert alert(long id, String nickname, long remaining, int daysLeft) {
        return new HomeResponse.PerformanceAlert(id, nickname, remaining, null, daysLeft);
    }

    private static BalanceForecast.SeriesPoint point(LocalDate date, long confirmed, long expected, Long conservative) {
        return new BalanceForecast.SeriesPoint(date, confirmed, expected, conservative, List.of());
    }
}
