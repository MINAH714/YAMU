package com.yamu.be.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.dto.CardListResponse;
import com.yamu.be.forecast.BalanceForecast;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** 홈의 작은 규칙(위험 시작일, 할 일 순서, 동기화 결과 저장 형식)을 DB 없이 확인한다. */
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
    void 할_일은_미납_다음_실적_부족이고_두_개까지다() {
        CardListResponse cards = cards(card(1L, "노리2", true), card(2L, "딥드림", false));
        List<HomeResponse.PerformanceAlert> alerts = List.of(alert(2L, "딥드림", 180_000L, 11),
                alert(3L, "탄탄대로", 50_000L, 11));

        assertThat(HomeService.todos(cards, alerts)).containsExactly(
                new HomeResponse.Todo("노리2 청구서가 미납됐어요", "미납", "ERROR", 1L),
                new HomeResponse.Todo("딥드림 실적까지 180,000원 남았어요", "마감 D-11", "WARNING", 2L));
    }

    @Test
    void 카드_목록이나_실적_영역이_실패하면_남은_쪽으로만_할_일을_만든다() {
        CardListResponse cards = cards(card(1L, "노리2", true));
        List<HomeResponse.PerformanceAlert> alerts = List.of(alert(2L, "딥드림", 180_000L, 0));

        assertThat(HomeService.todos(null, alerts)).extracting(HomeResponse.Todo::title, HomeResponse.Todo::tag)
                .containsExactly(Tuple.tuple("딥드림 실적까지 180,000원 남았어요", "마감 D-0"));
        assertThat(HomeService.todos(cards, null)).extracting(HomeResponse.Todo::userCardId).containsExactly(1L);
        assertThat(HomeService.todos(null, null)).isEmpty();
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

    private static CardListResponse cards(CardListResponse.CardSummary... cards) {
        return new CardListResponse(List.of(cards), null);
    }

    private static CardListResponse.CardSummary card(long id, String nickname, boolean unpaid) {
        return new CardListResponse.CardSummary(id, nickname, null, nickname, null, null, null, null, false, List.of(),
                null, new CardListResponse.BillingSummary(14, 0L, unpaid), null);
    }

    private static HomeResponse.PerformanceAlert alert(long id, String nickname, long remaining, int daysLeft) {
        return new HomeResponse.PerformanceAlert(id, nickname, remaining, null, daysLeft);
    }

    private static BalanceForecast.SeriesPoint point(LocalDate date, long confirmed, long expected, Long conservative) {
        return new BalanceForecast.SeriesPoint(date, confirmed, expected, conservative, List.of());
    }
}
