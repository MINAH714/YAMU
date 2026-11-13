package com.yamu.be.assetflow;

import com.yamu.be.forecast.BalanceForecast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 금액 null은 자료 없음이며 0원과 구분한다. 미래 confirmed도 실제 관측 잔고가 아니다. */
public record AssetFlowResponse(YearMonth yearMonth, List<Long> accountIds, Long currentTotalBalance,
        Instant balanceAsOf, List<Point> series, LocalDate forecastFrom, LocalDate forecastTo,
        List<PartialError> partialErrors, Instant calculatedAt) {
    public AssetFlowResponse {
        accountIds = List.copyOf(accountIds);
        series = List.copyOf(series);
        partialErrors = List.copyOf(partialErrors);
    }
    public enum Kind { ACTUAL, CURRENT, FORECAST }
    public enum DataStatus { AVAILABLE, NO_DATA, ERROR }
    public record Forecast(long confirmed, long expected, Long conservative) {}
    public record Point(LocalDate date, Kind kind, DataStatus dataStatus, Long displayBalance,
            Long actualBalance, Forecast forecast, List<BalanceForecast.Event> events) {
        public Point { events = List.copyOf(events); }
    }
    public record PartialError(Long accountId, LocalDate from, LocalDate to, String reason) {}
}
