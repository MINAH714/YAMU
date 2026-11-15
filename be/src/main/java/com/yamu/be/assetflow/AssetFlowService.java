package com.yamu.be.assetflow;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.forecast.BalanceForecast;
import com.yamu.be.forecast.ForecastService;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import static com.yamu.be.assetflow.AssetFlowResponse.*;

@Service
public class AssetFlowService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final LinkedAccountRepository accounts;
    private final AppUserRepository users;
    private final PersonalDataCipher cipher;
    private final AccountBalanceService balances;
    private final ForecastService forecasts;
    private final AssetFlowHistoryReader history;
    private final Clock clock;
    public AssetFlowService(LinkedAccountRepository accounts, AppUserRepository users, PersonalDataCipher cipher,
            AccountBalanceService balances, ForecastService forecasts, AssetFlowHistoryReader history, Clock clock) {
        this.accounts = accounts; this.users = users; this.cipher = cipher;
        this.balances = balances; this.forecasts = forecasts; this.history = history; this.clock = clock;
    }

    public AssetFlowResponse get(Long userId, YearMonth requestedMonth, List<Long> requestedIds, boolean refresh) {
        if (userId == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        LocalDate today = clock.instant().atZone(KOREA).toLocalDate();
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth month = requestedMonth == null ? currentMonth : requestedMonth;
        if (month.isBefore(currentMonth.minusMonths(11)) || month.isAfter(currentMonth.plusMonths(1))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        List<LinkedAccount> selected = select(userId, requestedIds);
        List<Long> ids = selected.stream().map(LinkedAccount::getId).toList();
        var errors = new ArrayList<PartialError>();
        var actuals = new ArrayList<Map<LocalDate, Long>>();
        Long current = null;
        Instant asOf = null;
        BalanceForecast forecast = null;
        if (!ids.isEmpty()) {
            if (!month.atEndOfMonth().isBefore(today)) {
                // 현재와 미래는 별도로 잔액을 읽어 이어 붙이지 않고 같은 시작 잔액을 공유한다.
                forecast = forecasts.preview(userId, ids, BalanceForecast.Until.DAYS, 30, true, refresh);
                current = forecast.currentTotalBalance();
                asOf = forecast.accountForecasts().stream().map(a -> a.syncedAt().toInstant(ZoneOffset.UTC))
                        .min(Instant::compareTo).orElseThrow();
                if (!forecast.horizon().from().equals(today)) throw new BusinessException(ErrorCode.FORECAST_DATE_CHANGED);
            } else {
                List<AccountBalance> read = balances.getBalances(userId, ids, refresh);
                current = read.stream().map(AccountBalance::balance).reduce(0L, Math::addExact);
                asOf = read.stream().map(a -> a.fetchedAt().toInstant(ZoneOffset.UTC)).min(Instant::compareTo).orElseThrow();
            }
            if (month.atDay(1).isBefore(today)) {
                LocalDate to = month.atEndOfMonth().isBefore(today) ? month.atEndOfMonth() : today.minusDays(1);
                String userKey = users.findById(userId).map(u -> u.getSsafyUserKey())
                        .map(value -> cipher.decrypt(value, CipherField.APP_USER_SSAFY_USER_KEY))
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                for (LinkedAccount account : selected) {
                    var available = new HashMap<LocalDate, Long>();
                    try {
                        var snapshot = history.read(userKey, account, month.atDay(1), to, deadline);
                        var result = new DailyBalanceReconstructor().reconstruct(snapshot, null);
                        result.points().stream().filter(p -> p.balance() != null)
                                .forEach(p -> available.put(p.date(), p.balance()));
                        if (result.reason() != DailyBalanceReconstructor.Reason.OK) {
                            errors.add(new PartialError(account.getId(), month.atDay(1), to, result.reason().name()));
                        }
                    } catch (BusinessException failure) {
                        if (!isExternalFailure(failure.getErrorCode().name())) throw failure;
                        errors.add(new PartialError(account.getId(), month.atDay(1), to, failure.getErrorCode().name()));
                    }
                    actuals.add(available);
                }
            }
        }
        Map<LocalDate, BalanceForecast.SeriesPoint> future = forecast == null ? Map.of()
                : forecast.series().stream().collect(Collectors.toMap(BalanceForecast.SeriesPoint::date, p -> p));
        var points = new ArrayList<Point>();
        for (LocalDate date = month.atDay(1); !date.isAfter(month.atEndOfMonth()); date = date.plusDays(1)) {
            Kind kind = date.isBefore(today) ? Kind.ACTUAL : date.equals(today) ? Kind.CURRENT : Kind.FORECAST;
            Long value = null;
            Forecast prediction = null;
            List<BalanceForecast.Event> events = List.of();
            DataStatus status = DataStatus.NO_DATA;
            if (kind == Kind.ACTUAL && !ids.isEmpty()) {
                long total = 0;
                boolean complete = actuals.size() == ids.size();
                for (var account : actuals) {
                    Long balance = account.get(date);
                    if (balance == null) { complete = false; break; }
                    total = Math.addExact(total, balance);
                }
                if (complete) value = total;
                else if (errors.stream().anyMatch(e -> isExternalFailure(e.reason()))) status = DataStatus.ERROR;
            } else if (kind == Kind.CURRENT) {
                value = current;
            } else if (kind == Kind.FORECAST && future.containsKey(date)) {
                var p = future.get(date);
                value = p.conservative() == null ? p.expected() : p.conservative();
                prediction = new Forecast(p.confirmed(), p.expected(), p.conservative());
                events = p.events();
            }
            if (value != null) status = DataStatus.AVAILABLE;
            points.add(new Point(date, kind, status, value, kind == Kind.FORECAST ? null : value, prediction, events));
        }
        if (!clock.instant().atZone(KOREA).toLocalDate().equals(today)) {
            throw new BusinessException(ErrorCode.FORECAST_DATE_CHANGED);
        }
        return new AssetFlowResponse(month, ids, current, asOf, points,
                forecast == null ? null : today.plusDays(1), forecast == null ? null : forecast.horizon().to(),
                errors, clock.instant());
    }

    private boolean isExternalFailure(String reason) {
        return reason.equals("UPSTREAM_TIMEOUT") || reason.equals("FIN_API_UNAVAILABLE") || reason.equals("FIN_API_ERROR");
    }

    private List<LinkedAccount> select(Long userId, List<Long> requested) {
        if (requested == null) return accounts.findByUserIdAndPrimaryTrue(userId).stream()
                .filter(LinkedAccount::isConnected).toList();
        if (requested.isEmpty() || requested.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(requested));
        if (ids.size() > 20) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var selected = new ArrayList<LinkedAccount>();
        for (Long id : ids) {
            selected.add(accounts.findByIdAndUserId(id, userId).filter(LinkedAccount::isConnected)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        }
        return List.copyOf(selected);
    }
}
