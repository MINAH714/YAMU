package com.yamu.be.forecast;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.AccountQueryService;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 선택 계좌의 날짜별 잔고예보를 만든다. 계좌마다 현재 잔액에 예정 수입·지출과 카드 청구 출금을 더하고,
 * 예상 생활비(AI 또는 TSB, {@link LivingCostProjector})로 보수적 선을 낸 뒤 같은 날짜끼리 합한다. 통합 선과 계좌별 선에서
 * 안전 잔액 미달을 찾는다. 생활비의 평균·월별 합계는 지난 3개월 실적 요약({@link LivingCostEstimator})이다.
 *
 * <p>잔액·카드는 BE 1 서비스, 신용카드 출금일은 BE 2 청구 주기({@link CreditWithdrawalSchedule})를 쓴다. 청구({@link CardBillReader})는
 * 임시 원천(진행 중 청구서만)이고, 생활비({@link LivingCostReader})는 체크카드 LIVING 결제({@link PaymentLivingCostReader})다. 안전 잔액·기간 기본값은
 * 잔고예보 설정({@link ForecastSettingsStore})에서, 다음 월급일은 설정의 월급 항목({@link ForecastSettingsService})에서 읽는다.
 */
@Service
public class ForecastService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 14, 30);
    /** 한 번에 예보할 수 있는 계좌 수. 계좌마다 금융망 잔액 조회가 나가므로 막는다. AI 생활비 API 입력 상한과 같다(2026-11-04 결정). */
    static final int MAX_ACCOUNTS = 20;
    /** 월급을 설정하지 않았거나 두 달 안에 회차가 없으면 NEXT_INCOME 대신 쓰는 명세의 대체 기간. */
    private static final int NEXT_INCOME_FALLBACK_DAYS = 30;
    /** 명세 「생활비 예측 상세」의 제외 안내. 실제로 빼는 일은 생활비 원천({@link LivingCostReader})이 한다. */
    private static final List<LivingCostForecast.Exclusion> LIVING_COST_EXCLUSIONS = List.of(
            new LivingCostForecast.Exclusion("RECURRING", "월세·통신비·구독료는 예정 지출로 별도 반영"),
            new LivingCostForecast.Exclusion("CREDIT_PAYMENT", "신용카드 청구는 출금일에 별도 반영"),
            new LivingCostForecast.Exclusion("UNCLASSIFIED", "소비 목적을 확인할 수 없는 계좌 출금은 임의로 포함하지 않음"),
            new LivingCostForecast.Exclusion("LARGE", "할부, 100만 원 초과, 평소 건당 소비의 10배 이상인 큰 결제는 평균에서 뺌"));

    private final CashflowService cashflows;
    private final AccountBalanceService balances;
    private final CardBillReader bills;
    private final CardQueryService cards;
    private final CreditWithdrawalSchedule creditSchedule;
    private final LivingCostReader livingCosts;
    private final AccountQueryService accounts;
    private final ForecastSettingsStore settings;
    private final ForecastSettingsService settingsService;
    private final LivingCostProjector projector;
    private final Clock clock;

    ForecastService(CashflowService cashflows, AccountBalanceService balances, CardBillReader bills,
            CardQueryService cards, CreditWithdrawalSchedule creditSchedule, LivingCostReader livingCosts,
            AccountQueryService accounts, ForecastSettingsStore settings, ForecastSettingsService settingsService,
            LivingCostProjector projector, Clock clock) {
        this.cashflows = cashflows;
        this.balances = balances;
        this.bills = bills;
        this.cards = cards;
        this.creditSchedule = creditSchedule;
        this.livingCosts = livingCosts;
        this.accounts = accounts;
        this.settings = settings;
        this.settingsService = settingsService;
        this.projector = projector;
        this.clock = clock;
    }

    /**
     * 선택 계좌의 통합 예보와, 자세히 보기면 계좌별 예보를 돌려준다.
     * 잔액을 하나라도 못 읽으면 일부만 합친 예보를 주지 않고 실패한다(FR-033). 생활비 자료가 없는 계좌가 있으면
     * 보수적 선을 비우고 확정·예상 선으로만 위험을 판단한다.
     *
     * @param accountIds            예보에 쓸 연결 계좌(중복을 빼고 최대 {@value #MAX_ACCOUNTS}개). 중복은 한 번만 쓴다
     * @param until                 기간 기준. null이면 잔고예보 설정의 기간 기준
     * @param days                  until=DAYS일 때 7·14·30. null이면 잔고예보 설정의 기본 일수
     * @param includeAccountDetails 계좌별 결과 포함 여부
     * @param refresh               캐시를 건너뛰고 잔액을 새로 읽을지
     * @return 날짜별 잔고예보
     */
    public BalanceForecast forecast(Long authenticatedUserId, List<Long> accountIds, BalanceForecast.Until until,
            Integer days, boolean includeAccountDetails, boolean refresh) {
        requireUser(authenticatedUserId);
        List<Long> selected = selectedAccounts(accountIds);
        ForecastSettings userSettings = settings.read(authenticatedUserId);
        long alertThreshold = userSettings.alertThreshold();
        LocalDate today = today();
        BalanceForecast.Horizon horizon = horizon(authenticatedUserId, userSettings, until, days, today);

        // 잔액·생활비는 금융망을 부를 수 있어 DB를 읽기 전에 따로 읽는다. 남의·연결 해제 계좌는 BE 1이 404로 거절한다.
        Map<Long, AccountBalance> balanceById = readBalances(authenticatedUserId, selected, refresh);
        LivingCostRead living = estimateLivingCost(authenticatedUserId, selected, today, horizon);
        List<CashflowService.Entry> entries = cashflows.list(authenticatedUserId, horizon.from(), horizon.to(),
                null, CashflowItem.Status.CONFIRMED, true);
        Set<Long> selectedSet = Set.copyOf(selected);
        CashflowEvents.Converted converted = CashflowEvents.convert(authenticatedUserId, entries, selectedSet, cards,
                creditSchedule, horizon.from(), horizon.to());
        List<FlowEvent> events = new ArrayList<>(converted.events());
        List<ExcludedItem> excluded = new ArrayList<>(converted.excluded());
        events.addAll(billEvents(authenticatedUserId, selected, selectedSet, horizon, excluded));

        Map<Long, Long> startBalances = new LinkedHashMap<>();
        Map<Long, Long> dailyLiving = new HashMap<>();
        for (Long accountId : selected) {
            startBalances.put(accountId, balanceById.get(accountId).balance());
            Long daily = living.projection().byAccount().get(accountId).dailyAmount();
            if (daily != null) {
                dailyLiving.put(accountId, daily);
            }
        }
        ForecastCalculator.Projection projection = ForecastCalculator.project(startBalances, events,
                horizon.from(), horizon.to(), dailyLiving);

        RiskDetector.Assessment aggregate = RiskDetector.assess(projection.total(), alertThreshold);
        Map<Long, RiskDetector.Assessment> byAccount = new LinkedHashMap<>();
        selected.forEach(accountId ->
                byAccount.put(accountId, RiskDetector.assess(projection.byAccount().get(accountId), alertThreshold)));
        BalanceForecast.Summary summary = summary(aggregate, byAccount.values(), projection.total());

        List<BalanceForecast.AccountForecast> accountForecasts = null;
        if (includeAccountDetails) {
            accountForecasts = selected.stream()
                    .map(accountId -> {
                        RiskDetector.Assessment assessment = byAccount.get(accountId);
                        return new BalanceForecast.AccountForecast(accountId, balanceById.get(accountId).balance(),
                                balanceById.get(accountId).fetchedAt(),
                                new BalanceForecast.AccountSummary(assessment.status(), assessment.minBalance(),
                                        assessment.minBalanceDate(), assessment.endBalance()),
                                series(projection.byAccount().get(accountId)), assessment.windows());
                    })
                    .toList();
        }
        LivingCostEstimator.Total history = living.estimate().total();
        LivingCostProjector.Total projected = living.projection().total();
        BalanceForecast.LivingCost livingCost = new BalanceForecast.LivingCost(projected.projectedAmount(),
                history.dailyAverage(), history.monthlyAverage(), projected.method(), projected.dataOrigin(),
                projected.confidence());
        long currentTotal = startBalances.values().stream().reduce(0L, Math::addExact);
        return new BalanceForecast(selected, selected.size(), currentTotal, horizon, alertThreshold, summary,
                series(projection.total()), aggregate.windows(), livingCost, accountForecasts, List.copyOf(excluded),
                true, now());
    }

    /**
     * 선택 계좌의 예상 생활비와 계산 근거(생활비 예측 상세)를 돌려준다. 기간은 같은 until·days로 부른 잔고예보와 같다
     * (보내지 않으면 잔고예보 설정의 기간 기준·기본 일수·다음 월급일). 하루 목표는 설정 값을 그대로 보여 주고 예측에 섞지 않는다.
     * 선택한 어느 계좌에도 생활비 자료가 없으면 0원으로 확정하지 않고 422 DATA_INSUFFICIENT다.
     * 생활비 원천이 소비 분류를 쓰므로 읽기 전용 트랜잭션 안에서 부르지 않는다.
     *
     * @param accountIds            생활비를 볼 연결 계좌. 중복은 한 번만 쓴다
     * @param until                 기간 기준. null이면 잔고예보 설정의 기간 기준
     * @param days                  until=DAYS일 때 7·14·30. null이면 잔고예보 설정의 기본 일수
     * @param includeAccountDetails 계좌별 생활비 포함 여부
     * @return 생활비 예측 상세
     */
    public LivingCostForecast livingCost(Long authenticatedUserId, List<Long> accountIds, BalanceForecast.Until until,
            Integer days, boolean includeAccountDetails) {
        requireUser(authenticatedUserId);
        List<Long> selected = selectedAccounts(accountIds);
        ForecastSettings userSettings = settings.read(authenticatedUserId);
        LocalDate today = today();
        // 잘못된 기간은 계좌·원천을 읽기 전에 400으로 거절한다(잔고예보와 같은 검사).
        BalanceForecast.Horizon horizon = horizon(authenticatedUserId, userSettings, until, days, today);
        // 잔액을 읽지 않으므로 소유·연결 확인을 여기서 한다. 남의·연결 해제 계좌는 404다.
        selected.forEach(accountId -> accounts.requireConnectedAccount(authenticatedUserId, accountId));
        LivingCostRead read = estimateLivingCost(authenticatedUserId, selected, today, horizon);
        LivingCostEstimator.Estimate estimate = read.estimate();
        LivingCostProjector.Projection projection = read.projection();
        boolean anyData = projection.byAccount().values().stream()
                .anyMatch(account -> account.dataOrigin() != LivingCostEstimator.DataOrigin.INSUFFICIENT);
        if (!anyData) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT);
        }

        LivingCostEstimator.Total total = estimate.total();
        LivingCostProjector.Total projected = projection.total();
        List<LivingCostForecast.AccountLivingCost> accountLivingCosts = null;
        if (includeAccountDetails) {
            accountLivingCosts = selected.stream()
                    .map(accountId -> {
                        LivingCostEstimator.AccountEstimate history = estimate.byAccount().get(accountId);
                        LivingCostProjector.AccountProjection account = projection.byAccount().get(accountId);
                        return new LivingCostForecast.AccountLivingCost(accountId, history.dailyAverage(),
                                history.monthlyAverage(), account.projectedAmount(), account.method(),
                                account.dataOrigin());
                    })
                    .toList();
        }
        LivingCostForecast.PaceCheck pace = total.pace() == null ? null
                : new LivingCostForecast.PaceCheck(total.pace().thisMonthDaily(), total.pace().averageDaily(),
                        total.pace().overRatio(), total.pace().flagged(), now());
        return new LivingCostForecast(selected, projected.projectedAmount(), total.dailyAverage(),
                total.monthlyAverage(), projected.method(), projected.dataOrigin(), projected.confidence(),
                new LivingCostForecast.Period(horizon.from(), horizon.to(), horizon.days(), horizon.type()),
                total.sources().stream()
                        .map(source -> new LivingCostForecast.Source(source.month(), source.amount())).toList(),
                LIVING_COST_EXCLUSIONS, userSettings.dailyLivingCostTarget(), pace, read.syncedAt(), accountLivingCosts);
    }

    /**
     * 요청 값이 없으면 잔고예보 설정으로 예보 기간을 정한다. 오늘 들어올 월급은 잔액에 이미 들어왔는지 몰라 예보에서 빼므로,
     * 다음 월급일은 오늘보다 뒤의 첫 회차다.
     */
    private BalanceForecast.Horizon horizon(Long userId, ForecastSettings userSettings, BalanceForecast.Until until,
            Integer days, LocalDate today) {
        BalanceForecast.Until effectiveUntil = until != null ? until : userSettings.horizon();
        Optional<LocalDate> nextIncome = effectiveUntil == BalanceForecast.Until.NEXT_INCOME
                ? settingsService.nextIncomeDate(userId, userSettings.incomeCashflowItemId(), today)
                : Optional.empty();
        return horizon(effectiveUntil, days != null ? days : userSettings.defaultDays(), nextIncome, today);
    }

    /**
     * 일별 생활 소비를 한 번 읽어 지난 3개월 실적 요약(AVG_3M)과 예보 기간의 예상 생활비(AI·TSB)를 함께 낸다.
     * 범위는 (이번 달 − 3개월)의 1일과 오늘부터 90일 전 중 이른 날부터 오늘까지다(AI 입력은 어제까지 90일).
     * 원천 실패는 그대로 던진다. AI 서버 실패는 던지지 않고 TSB로 계산한다({@link LivingCostProjector}).
     */
    private LivingCostRead estimateLivingCost(Long userId, List<Long> selected, LocalDate today,
            BalanceForecast.Horizon horizon) {
        LocalDate monthsFrom = YearMonth.from(today).minusMonths(LivingCostEstimator.MONTHS).atDay(1);
        LocalDate historyFrom = today.minusDays(LivingCostProjector.HISTORY_DAYS);
        LocalDate from = historyFrom.isBefore(monthsFrom) ? historyFrom : monthsFrom;
        LivingCostReader.Spending spending = livingCosts.dailySpending(userId, selected, from, today);
        // 원천 계약 검사(음수·중복·미래 날짜)는 실적 요약이 먼저 한다.
        LivingCostEstimator.Estimate estimate = LivingCostEstimator.estimate(selected, spending.days(), today);
        LivingCostProjector.Projection projection = projector.project(selected, spending.days(), today,
                horizon.days());
        return new LivingCostRead(estimate, projection, spending.syncedAt());
    }

    /**
     * 통합 수준과 계좌별 수준 중 가장 나쁜 것을 최종 수준으로 한다. 통합이 안전해도 위험한 계좌가 있으면 그 수준이 된다.
     */
    private static BalanceForecast.Summary summary(RiskDetector.Assessment aggregate,
            Iterable<RiskDetector.Assessment> accountAssessments, List<ForecastCalculator.BalancePoint> total) {
        int atRisk = 0;
        for (RiskDetector.Assessment account : accountAssessments) {
            if (account.status() != RiskDetector.Level.SAFE) {
                atRisk++;
            }
        }
        RiskDetector.Level status = RiskDetector.overall(aggregate, accountAssessments);
        return new BalanceForecast.Summary(status, aggregate.status(), aggregate.minBalance(),
                aggregate.minBalanceDate(), aggregate.endBalance(), atRisk > 0, atRisk, largestOutflow(total));
    }

    /** 통합 출금 합이 가장 큰 날. 같으면 이른 날이다. */
    private static BalanceForecast.Outflow largestOutflow(List<ForecastCalculator.BalancePoint> total) {
        BalanceForecast.Outflow largest = null;
        for (ForecastCalculator.BalancePoint point : total) {
            long outflow = 0;
            for (FlowEvent event : point.events()) {
                if (event.amount() < 0) {
                    outflow = Math.addExact(outflow, event.amount());
                }
            }
            if (outflow < 0 && (largest == null || outflow < largest.amount())) {
                largest = new BalanceForecast.Outflow(point.date(), "예정 출금 합계", outflow);
            }
        }
        return largest;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KOREA));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private static void requireUser(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** 중복을 뺀 선택 계좌. 비었으면 명세의 ACCOUNT_SELECTION_REQUIRED다. */
    private static List<Long> selectedAccounts(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCOUNT_SELECTION_REQUIRED);
        }
        for (Long accountId : accountIds) {
            if (accountId == null || accountId <= 0) {
                throw invalid("accountIds", "올바른 계좌를 선택해 주세요.");
            }
        }
        List<Long> selected = List.copyOf(new LinkedHashSet<>(accountIds));
        if (selected.size() > MAX_ACCOUNTS) {
            throw invalid("accountIds", "계좌는 최대 " + MAX_ACCOUNTS + "개까지 선택할 수 있습니다.");
        }
        return selected;
    }

    /**
     * NEXT_INCOME은 다음 월급일 전날까지다(명세). 월급일이 내일이면 오늘 하루뿐이다(2026-11-04 결정: 명세대로).
     * 다음 월급일을 모르면 명세의 대체 기간 30일을 DAYS로 계산한다.
     * days는 기간 기준과 상관없이 보내면 늘 7·14·30인지 검사한다. NEXT_INCOME이면 검사만 하고 쓰지 않는다(2026-11-04 결정).
     */
    private static BalanceForecast.Horizon horizon(BalanceForecast.Until until, Integer days,
            Optional<LocalDate> nextIncome, LocalDate today) {
        if (days != null && !ALLOWED_DAYS.contains(days)) {
            throw invalid("days", "예보 기간은 7, 14, 30일 중에서 골라 주세요.");
        }
        if (until == null || until == BalanceForecast.Until.NEXT_INCOME) {
            if (nextIncome.isPresent()) {
                LocalDate to = nextIncome.get().minusDays(1);
                return new BalanceForecast.Horizon(BalanceForecast.Until.NEXT_INCOME, today, to,
                        (int) ChronoUnit.DAYS.between(today, to));
            }
            return new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, today,
                    today.plusDays(NEXT_INCOME_FALLBACK_DAYS), NEXT_INCOME_FALLBACK_DAYS);
        }
        if (days == null) {
            throw invalid("days", "예보 기간은 7, 14, 30일 중에서 골라 주세요.");
        }
        return new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, today, today.plusDays(days), days);
    }

    /**
     * BE 1 잔액 조회. 남의·연결 해제 계좌 404, SSAFY 회원 아님 400, 새로고침 한도 429, 금융망 502·504는 그대로 전파한다.
     * refresh=false면 BE 1의 10분 캐시를 쓰고, true일 때만 캐시를 건너뛰고 새로고침 한도를 센다.
     */
    private Map<Long, AccountBalance> readBalances(Long userId, List<Long> selected, boolean refresh) {
        Map<Long, AccountBalance> byId = new HashMap<>();
        for (AccountBalance balance : balances.getBalances(userId, selected, refresh)) {
            byId.put(balance.linkedAccountId(), balance);
        }
        for (Long accountId : selected) {
            // BE 1은 전부 주거나 예외를 던져 보통 오지 않는 방어 분기다. 빠진 잔액을 0원으로 채우지 않는다.
            if (!byId.containsKey(accountId)) {
                throw new BusinessException(ErrorCode.DATA_INSUFFICIENT);
            }
        }
        return byId;
    }

    /**
     * 청구 출금을 출금 계좌의 출금일에 뺀다. 확정 청구는 확정 선부터, 진행 중 청구는 예상 선부터 반영한다.
     * 출금일이 지났는데 아직 확정 상태인 청구는 출금 결과를 기다리는 중이라 오늘 뺀다.
     * 미납 청구는 재출금 일정이 없어 빼지 않고 excluded로 알린다.
     */
    private List<FlowEvent> billEvents(Long userId, List<Long> selected, Set<Long> selectedSet,
            BalanceForecast.Horizon horizon, List<ExcludedItem> excluded) {
        List<FlowEvent> events = new ArrayList<>();
        for (CardBillReader.OutstandingBill bill : bills.outstanding(userId, selected, horizon.to())) {
            // BE 2 데이터 계약 위반이다. 출금 계좌를 임의로 정하거나 조용히 넘기지 않는다.
            if (bill.accountId() == null || bill.status() == null || bill.withdrawalDate() == null) {
                throw new IllegalStateException("청구의 출금 계좌·상태·출금일이 비었다: " + bill.cardBillId());
            }
            if (bill.amount() < 0) {
                throw new IllegalStateException("청구 출금액이 음수다: " + bill.cardBillId());
            }
            // 계약상 오지 않지만, 다른 계좌의 청구를 선택 계좌에 옮겨 담지 않는다.
            if (!selectedSet.contains(bill.accountId())) {
                continue;
            }
            switch (bill.status()) {
                case UNPAID -> excluded.add(ExcludedItem.unpaid(bill));
                case CONFIRMED -> {
                    // 결과 불명이면 실제로는 이미 빠져 현재 잔액에 반영됐을 수 있다. 위험을 놓치지 않도록 그래도 뺀다.
                    LocalDate date = bill.withdrawalDate().isBefore(horizon.from())
                            ? horizon.from() : bill.withdrawalDate();
                    events.add(billEvent(bill, date, FlowEvent.Confidence.CONFIRMED));
                }
                case ACCUMULATING -> {
                    // 청구 확정·출금 배치(-206·-207)가 없어 출금일이 지나도 진행 중으로 남고 돈도 빠지지 않는다.
                    // 결국 빠질 돈이라 오늘 빠질 돈으로 본다(2026-11-04 BE 2 합의). 배치가 생기면 이 경우는 확정·미납으로 넘어간다.
                    LocalDate date = bill.withdrawalDate().isBefore(horizon.from())
                            ? horizon.from() : bill.withdrawalDate();
                    events.add(billEvent(bill, date, FlowEvent.Confidence.EXPECTED));
                }
            }
        }
        return events;
    }

    private static FlowEvent billEvent(CardBillReader.OutstandingBill bill, LocalDate date,
            FlowEvent.Confidence confidence) {
        return new FlowEvent(bill.accountId(), date, -bill.amount(), FlowEvent.Type.CARD_BILLING, confidence,
                "카드 청구 출금", bill.cardBillId());
    }

    private static List<BalanceForecast.SeriesPoint> series(List<ForecastCalculator.BalancePoint> points) {
        return points.stream()
                .map(point -> new BalanceForecast.SeriesPoint(point.date(), point.confirmed(), point.expected(),
                        point.conservative(),
                        point.events().stream()
                                .map(event -> new BalanceForecast.Event(event.type(), event.title(), event.amount(),
                                        event.confidence()))
                                .toList()))
                .toList();
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }

    /** 생활비 추정과 원천을 읽은 시각. */
    private record LivingCostRead(LivingCostEstimator.Estimate estimate, LivingCostProjector.Projection projection,
            LocalDateTime syncedAt) {}
}
