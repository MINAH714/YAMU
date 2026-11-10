package com.yamu.be.home;

import com.yamu.be.card.CardManagementService;
import com.yamu.be.card.dto.CardListResponse;
import com.yamu.be.performance.CardPerformancesResponse;
import com.yamu.be.performance.PerformanceService;
import com.yamu.be.performance.PerformanceStatus;
import com.yamu.be.forecast.BalanceForecast;
import com.yamu.be.forecast.ForecastService;
import com.yamu.be.forecast.ForecastSettingsResponse;
import com.yamu.be.forecast.ForecastSettingsService;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.report.MonthlyReportResponse;
import com.yamu.be.report.ReportService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 홈 요약({@code GET /api/home}). 카드·잔고예보·알림·결제 조회를 모아 한 번에 돌려준다.
 *
 * <ul>
 *   <li>영역마다 따로 읽고, 한 영역이 실패하면 그 칸만 비우고 {@code partialErrors}에 적는다. 응답은 200이다</li>
 *   <li>잔고예보는 금융망을 부를 수 있어(캐시가 없으면) 이 클래스에 {@code @Transactional}을 붙이지 않는다. 각 조회가 자기 트랜잭션을 쓴다</li>
 *   <li>홈은 잔액 캐시(10분)를 쓴다. 새로 읽기는 {@code POST /api/sync}({@link SyncService})가 한다</li>
 * </ul>
 */
@Slf4j
@Service
public class HomeService {

    /** 앱이 그리는 할 일 줄 수(HomeSummary.kt MAX_TODOS). */
    static final int MAX_TODOS = 2;
    /** 오늘 쓸 카드를 고를 때 보는 기간. */
    static final Duration TODAY_CARD_WINDOW = Duration.ofDays(30);

    private final CardManagementService cards;
    private final ForecastService forecasts;
    private final ForecastSettingsService forecastSettings;
    private final ReportService reports;
    private final PerformanceService performances;
    private final HomeReader reader;
    private final Clock clock;

    HomeService(CardManagementService cards, ForecastService forecasts, ForecastSettingsService forecastSettings,
            ReportService reports, PerformanceService performances, HomeReader reader, Clock clock) {
        this.cards = cards;
        this.forecasts = forecasts;
        this.forecastSettings = forecastSettings;
        this.reports = reports;
        this.performances = performances;
        this.reader = reader;
        this.clock = clock;
    }

    public HomeResponse home(AuthUser me) {
        long userId = me.userId();
        List<HomeResponse.PartialError> errors = new ArrayList<>();

        CardListResponse cardList = section("cards", errors, () -> cards.list(me, null));
        List<HomeResponse.PerformanceAlert> alerts = section("performanceAlerts", errors,
                () -> performanceAlerts(userId));
        ForecastPart forecast = section("balanceForecast", errors, () -> forecast(userId));
        HomeResponse.MonthSummary monthSummary = section("monthSummary", errors, () -> monthSummary(userId));
        Integer unread = section("unreadNotificationCount", errors, () -> reader.unreadNotificationCount(userId));
        List<HomeResponse.Todo> todos = section("todos", errors, () -> todos(cardList, alerts));
        HomeResponse.RecentPayment recentPayment = section("recentPayment", errors,
                () -> reader.recentPayment(userId).orElse(null));
        HomeResponse.Onboarding onboarding = section("onboarding", errors,
                () -> new HomeResponse.Onboarding(reader.hasConnectedAccount(userId) ? "DONE" : "LINK"));
        HomeResponse.TodayCard todayCard = section("todayCard", errors,
                () -> reader.todayCard(userId, now().minus(TODAY_CARD_WINDOW)).orElse(null));

        return new HomeResponse(
                cardList == null ? null : cards(cardList),
                alerts == null ? List.of() : alerts,
                forecast == null ? null : forecast.summary(),
                monthSummary,
                unread == null ? 0 : unread,
                List.copyOf(errors),
                forecast == null ? null : forecast.syncedAt(),
                todos == null ? List.of() : todos,
                recentPayment,
                onboarding,
                todayCard);
    }

    /**
     * 영역 하나를 읽는다. 실패하면 null을 돌려주고 errors에 적는다. 예상한 실패(금융망 실패 등)는 그 에러 코드를,
     * 예상하지 못한 오류는 INTERNAL_ERROR를 적는다. 다른 영역은 계속 읽는다.
     */
    private static <T> T section(String area, List<HomeResponse.PartialError> errors, Supplier<T> read) {
        try {
            return read.get();
        } catch (BusinessException e) {
            log.warn("홈 영역 실패 area={} code={}", area, e.getErrorCode());
            errors.add(new HomeResponse.PartialError(area, e.getErrorCode().name()));
        } catch (RuntimeException e) {
            log.warn("홈 영역 실패 area={} code=INTERNAL_ERROR exceptionType={}", area, e.getClass().getName(), e);
            errors.add(new HomeResponse.PartialError(area, ErrorCode.INTERNAL_ERROR.name()));
        }
        return null;
    }

    private static HomeResponse.Cards cards(CardListResponse list) {
        List<HomeResponse.CardItem> items = list.cards().stream()
                .map(card -> new HomeResponse.CardItem(card.userCardId(), card.nickname(),
                        card.issuer() == null ? null : card.issuer().color(), card.maskedNumber(),
                        card.cardType() == null ? null : card.cardType().name()))
                .toList();
        return new HomeResponse.Cards(items.size(), items);
    }

    /**
     * 실적 부족 카드(2026-11-10 결정). 이번 달(한국 기준) 카드별 실적({@code GET /api/users/me/card-performances}, S15P31E103-373)에서
     * {@link PerformanceStatus#AT_RISK}인 카드만, 카드 목록 순서로. 달성률은 카드 목록과 같은 규칙({@link CardListResponse.Performance#of})이다.
     * 임시: 375(유불리 판단 기록)가 머지되면 "이번 달은 그대로 둘게요"로 판단한 카드를 뺀다.
     */
    private List<HomeResponse.PerformanceAlert> performanceAlerts(long userId) {
        CardPerformancesResponse overview = performances.overview(userId, null);
        return overview.cards().stream()
                .filter(card -> card.status() == PerformanceStatus.AT_RISK)
                .map(card -> new HomeResponse.PerformanceAlert(card.userCardId(), card.nickname(),
                        card.remainingAmount(),
                        CardListResponse.Performance.of(overview.yearMonth(), card.targetAmount(),
                                card.recognizedAmount()).achievementRate(),
                        overview.daysLeft()))
                .toList();
    }

    /**
     * 할 일(2026-11-10 결정). 최대 {@value #MAX_TODOS}개. 1순위 미납 청구서가 있는 카드(카드 목록 순서), 2순위 실적 부족 카드
     * ({@code performanceAlerts} 순서). 잔고 부족은 잔고예보 칸에 이미 나와 넣지 않는다. 실적 할 일 문구는 앱이
     * {@code performanceAlerts}로 할 일을 만들 때의 문구와 같다. 카드 목록이나 실적 영역이 실패하면 남은 쪽으로만 만든다.
     */
    static List<HomeResponse.Todo> todos(CardListResponse list, List<HomeResponse.PerformanceAlert> alerts) {
        Stream<HomeResponse.Todo> unpaid = list == null ? Stream.empty() : list.cards().stream()
                .filter(card -> card.billingSummary() != null && card.billingSummary().hasUnpaidBill())
                .map(card -> new HomeResponse.Todo(card.nickname() + " 청구서가 미납됐어요", "미납", "ERROR",
                        card.userCardId()));
        Stream<HomeResponse.Todo> performance = alerts == null ? Stream.empty() : alerts.stream()
                .map(alert -> new HomeResponse.Todo(
                        "%s 실적까지 %,d원 남았어요".formatted(alert.nickname(), alert.remainingAmount()),
                        alert.daysLeft() == null ? null : "마감 D-" + alert.daysLeft(), "WARNING",
                        alert.userCardId()));
        return Stream.concat(unpaid, performance).limit(MAX_TODOS).toList();
    }

    /**
     * 이번 달(한국 기준) 소비·혜택. 월간 리포트({@code GET /api/reports/monthly}, S15P31E103-372)와 같은 값이다. 소비는 즉시할인 전
     * 승인 금액, 혜택은 확정 전을 포함한 혜택 줄 원화 합(리포트 {@code benefitSummary.expected})이다.
     */
    private HomeResponse.MonthSummary monthSummary(long userId) {
        MonthlyReportResponse report = reports.monthly(userId, null);
        return new HomeResponse.MonthSummary(report.yearMonth(), report.totalSpent(), report.benefitSummary().expected());
    }

    /** 기준 계좌의 잔고예보. 기준 계좌가 없으면 null(실패가 아님). 기간은 잔고예보 설정을 따른다. */
    private ForecastPart forecast(long userId) {
        ForecastSettingsResponse settings = forecastSettings.get(userId);
        Long accountId = settings.baseAccountId();
        if (accountId == null) {
            return null;
        }
        BalanceForecast result = forecasts.forecast(userId, List.of(accountId), null, null, true, false);
        BalanceForecast.Horizon horizon = result.horizon();
        HomeResponse.NextIncome nextIncome = horizon.type() == BalanceForecast.Until.NEXT_INCOME
                && settings.income() != null
                ? new HomeResponse.NextIncome(horizon.to().plusDays(1), settings.income().amount(),
                        settings.income().title())
                : null;
        HomeResponse.BalanceForecast summary = new HomeResponse.BalanceForecast(accountId,
                result.currentTotalBalance(), result.summary().endBalance(),
                firstRiskDate(result.series(), result.alertThreshold()), horizon.days(),
                result.summary().minBalance(), nextIncome, result.isEstimate(),
                result.livingCost().projectedAmount() == null);
        return new ForecastPart(summary, result.accountForecasts().get(0).syncedAt());
    }

    /**
     * 잔액이 안전 잔액 밑으로 처음 내려가는 날. 잔고예보의 위험 구간과 같은 규칙(확정·예상·보수적 선 중 하나라도 기준 미만)이다.
     * 위험 구간 타입(RiskDetector)이 forecast 패키지 밖에 열려 있지 않아 공개된 날짜 선에서 다시 찾는다.
     */
    static LocalDate firstRiskDate(List<BalanceForecast.SeriesPoint> series, long threshold) {
        for (BalanceForecast.SeriesPoint point : series) {
            if (point.confirmed() < threshold || point.expected() < threshold
                    || (point.conservative() != null && point.conservative() < threshold)) {
                return point.date();
            }
        }
        return null;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private record ForecastPart(HomeResponse.BalanceForecast summary, LocalDateTime syncedAt) {}
}
