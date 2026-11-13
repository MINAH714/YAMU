package com.yamu.be.home;

import com.yamu.be.forecast.BalanceForecast;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** 기존 예보의 표시 날짜·대표선만 요약한다. 새로운 예보를 계산하거나 금융망을 호출하지 않는다. */
final class HomeForecastPreview {
    static final int DAYS = 30;

    static boolean reusable(BalanceForecast result, Long accountId, LocalDate today) {
        return result != null && result.accountIds().equals(List.of(accountId))
                && result.horizon().type() == BalanceForecast.Until.DAYS
                && result.horizon().days() == DAYS && result.horizon().from().equals(today)
                && result.horizon().to().equals(today.plusDays(DAYS));
    }

    static HomeResponse.ForecastPreview from(BalanceForecast result, Long accountId, LocalDate today) {
        if (!reusable(result, accountId, today)) throw new IllegalStateException("홈 30일 예보의 계좌·기간 불일치");
        LocalDate from = today.plusDays(1);
        LocalDate to = today.plusDays(DAYS);
        var points = result.series().stream().filter(p -> !p.date().isBefore(from) && !p.date().isAfter(to))
                .sorted(Comparator.comparing(BalanceForecast.SeriesPoint::date)).toList();
        if (points.size() != DAYS) throw new IllegalStateException("홈 30일 예보 날짜 누락");
        for (int i = 0; i < DAYS; i++) {
            if (!points.get(i).date().equals(from.plusDays(i))) throw new IllegalStateException("홈 30일 예보 날짜 중복·누락");
        }
        var min = points.stream().min(Comparator.comparingLong(HomeForecastPreview::display)
                .thenComparing(BalanceForecast.SeriesPoint::date)).orElseThrow();
        LocalDate risk = points.stream().filter(p -> display(p) < result.alertThreshold())
                .map(BalanceForecast.SeriesPoint::date).findFirst().orElse(null);
        var account = result.accountForecasts().stream().filter(a -> accountId.equals(a.accountId())).findFirst().orElseThrow();
        return new HomeResponse.ForecastPreview(accountId, result.currentTotalBalance(), from, to, DAYS, display(min),
                min.date(), risk, result.alertThreshold(), account.syncedAt(), result.calculatedAt(), result.isEstimate(),
                result.livingCost().projectedAmount() == null);
    }

    private static long display(BalanceForecast.SeriesPoint point) {
        return point.conservative() == null ? point.expected() : point.conservative();
    }
}
