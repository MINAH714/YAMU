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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 선택 계좌의 날짜별 잔고예보를 만든다. 계좌마다 현재 잔액에 예정 수입·지출과 카드 청구 출금을 더하고,
 * 최근 3개월 생활비로 보수적 선을 낸 뒤 같은 날짜끼리 합한다. 통합 선과 계좌별 선에서 안전 잔액 미달을 찾는다.
 *
 * <p>잔액·카드는 BE 1 서비스를 직접 쓴다. 청구(BE 2, {@link CardBillReader})·신용카드 출금일({@link CreditWithdrawalSchedule},
 * 7.2.1)·생활비({@link LivingCostReader}) 원천 구현이 아직 없어 Spring 빈으로 등록하지 않는다(등록하면 서버가 뜨지 않는다).
 * 원천이 붙는 MR에서 {@code @Service}·설정값·컨트롤러를 더한다.
 */
public class ForecastService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 14, 30);
    /** 다음 수입일을 정할 잔고예보 설정이 아직 없어 명세의 대체 기간을 쓴다. */
    private static final int NEXT_INCOME_FALLBACK_DAYS = 30;
    private static final String LIVING_COST_METHOD = "AVG_3M";
    /** 명세 「생활비 예측 상세」의 제외 안내. 실제로 빼는 일은 생활비 원천({@link LivingCostReader})이 한다. */
    private static final List<LivingCostForecast.Exclusion> LIVING_COST_EXCLUSIONS = List.of(
            new LivingCostForecast.Exclusion("RECURRING", "월세·통신비·구독료는 예정 지출로 별도 반영"),
            new LivingCostForecast.Exclusion("CREDIT_PAYMENT", "신용카드 청구는 출금일에 별도 반영"),
            new LivingCostForecast.Exclusion("UNCLASSIFIED", "소비 목적을 확인할 수 없는 계좌 출금은 임의로 포함하지 않음"));

    private final CashflowService cashflows;
    private final AccountBalanceService balances;
    private final CardBillReader bills;
    private final CardQueryService cards;
    private final CreditWithdrawalSchedule creditSchedule;
    private final LivingCostReader livingCosts;
    private final AccountQueryService accounts;
    private final long alertThreshold;
    private final Clock clock;

    /** @param alertThreshold 안전 잔액(원). 잔고예보 설정 API가 생기기 전까지 명세의 현행 가정값을 받는다 */
    ForecastService(CashflowService cashflows, AccountBalanceService balances, CardBillReader bills,
            CardQueryService cards, CreditWithdrawalSchedule creditSchedule, LivingCostReader livingCosts,
            AccountQueryService accounts, long alertThreshold, Clock clock) {
        this.cashflows = cashflows;
        this.balances = balances;
        this.bills = bills;
        this.cards = cards;
        this.creditSchedule = creditSchedule;
        this.livingCosts = livingCosts;
        this.accounts = accounts;
        this.alertThreshold = alertThreshold;
        this.clock = clock;
    }

    /**
     * 선택 계좌의 통합 예보와, 자세히 보기면 계좌별 예보를 돌려준다.
     * 잔액을 하나라도 못 읽으면 일부만 합친 예보를 주지 않고 실패한다(FR-033). 생활비 자료가 없는 계좌가 있으면
     * 보수적 선을 비우고 확정·예상 선으로만 위험을 판단한다.
     *
     * @param accountIds            예보에 쓸 연결 계좌. 중복은 한 번만 쓴다
     * @param until                 기간 기준. null이면 NEXT_INCOME
     * @param days                  until=DAYS일 때 7·14·30
     * @param includeAccountDetails 계좌별 결과 포함 여부
     * @param refresh               캐시를 건너뛰고 잔액을 새로 읽을지
     * @return 날짜별 잔고예보
     */
    public BalanceForecast forecast(Long authenticatedUserId, List<Long> accountIds, BalanceForecast.Until until,
            Integer days, boolean includeAccountDetails, boolean refresh) {
        requireUser(authenticatedUserId);
        List<Long> selected = selectedAccounts(accountIds);
        LocalDate today = today();
        BalanceForecast.Horizon horizon = horizon(until, days, today);

        // 잔액·생활비는 금융망을 부를 수 있어 DB를 읽기 전에 따로 읽는다. 남의·연결 해제 계좌는 BE 1이 404로 거절한다.
        Map<Long, AccountBalance> balanceById = readBalances(authenticatedUserId, selected, refresh);
        LivingCostEstimator.Estimate living = estimateLivingCost(authenticatedUserId, selected, today).estimate();
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
            Long daily = living.byAccount().get(accountId).dailyAverage();
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
        LivingCostEstimator.Total total = living.total();
        BalanceForecast.LivingCost livingCost = new BalanceForecast.LivingCost(projected(total.dailyAverage(), horizon),
                total.dailyAverage(), total.monthlyAverage(), LIVING_COST_METHOD, total.dataOrigin(), total.confidence());
        long currentTotal = startBalances.values().stream().reduce(0L, Math::addExact);
        return new BalanceForecast(selected, selected.size(), currentTotal, horizon, alertThreshold, summary,
                series(projection.total()), aggregate.windows(), livingCost, accountForecasts, List.copyOf(excluded),
                true, now());
    }

    /**
     * 선택 계좌의 예상 생활비와 계산 근거(생활비 예측 상세)를 돌려준다. 기간은 잔고예보 기본 기간(30일)이다.
     * 선택한 어느 계좌에도 생활비 자료가 없으면 0원으로 확정하지 않고 422 DATA_INSUFFICIENT다.
     *
     * @param accountIds            생활비를 볼 연결 계좌. 중복은 한 번만 쓴다
     * @param includeAccountDetails 계좌별 생활비 포함 여부
     * @return 생활비 예측 상세
     */
    public LivingCostForecast livingCost(Long authenticatedUserId, List<Long> accountIds,
            boolean includeAccountDetails) {
        requireUser(authenticatedUserId);
        List<Long> selected = selectedAccounts(accountIds);
        // 잔액을 읽지 않으므로 소유·연결 확인을 여기서 한다. 남의·연결 해제 계좌는 404다.
        selected.forEach(accountId -> accounts.requireConnectedAccount(authenticatedUserId, accountId));
        LocalDate today = today();
        BalanceForecast.Horizon horizon = horizon(null, null, today);
        LivingCostRead read = estimateLivingCost(authenticatedUserId, selected, today);
        LivingCostEstimator.Estimate estimate = read.estimate();
        boolean anyData = estimate.byAccount().values().stream()
                .anyMatch(account -> account.dataOrigin() != LivingCostEstimator.DataOrigin.INSUFFICIENT);
        if (!anyData) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT);
        }

        LivingCostEstimator.Total total = estimate.total();
        List<LivingCostForecast.AccountLivingCost> accountLivingCosts = null;
        if (includeAccountDetails) {
            accountLivingCosts = estimate.byAccount().values().stream()
                    .map(account -> new LivingCostForecast.AccountLivingCost(account.accountId(),
                            account.dailyAverage(), account.monthlyAverage(),
                            projected(account.dailyAverage(), horizon), account.dataOrigin()))
                    .toList();
        }
        LivingCostForecast.PaceCheck pace = total.pace() == null ? null
                : new LivingCostForecast.PaceCheck(total.pace().thisMonthDaily(), total.pace().averageDaily(),
                        total.pace().overRatio(), total.pace().flagged(), now());
        return new LivingCostForecast(selected, projected(total.dailyAverage(), horizon), total.dailyAverage(),
                total.monthlyAverage(), LIVING_COST_METHOD, total.dataOrigin(), total.confidence(),
                new LivingCostForecast.Period(horizon.from(), horizon.to(), horizon.days(), horizon.type()),
                total.sources().stream()
                        .map(source -> new LivingCostForecast.Source(source.month(), source.amount())).toList(),
                LIVING_COST_EXCLUSIONS, null, pace, read.syncedAt(), accountLivingCosts);
    }

    /**
     * (이번 달 − 3개월)의 1일부터 오늘까지 일별 생활 소비를 읽어 계좌별로 추정한다. 이 범위는 늘 90일 이상이다.
     * 원천 실패는 그대로 던진다.
     */
    private LivingCostRead estimateLivingCost(Long userId, List<Long> selected, LocalDate today) {
        LocalDate from = YearMonth.from(today).minusMonths(LivingCostEstimator.MONTHS).atDay(1);
        LivingCostReader.Spending spending = livingCosts.dailySpending(userId, selected, from, today);
        return new LivingCostRead(LivingCostEstimator.estimate(selected, spending.days(), today),
                spending.syncedAt());
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

    private static Long projected(Long dailyAverage, BalanceForecast.Horizon horizon) {
        return dailyAverage == null ? null : Math.multiplyExact(dailyAverage.longValue(), horizon.days());
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
        return List.copyOf(new LinkedHashSet<>(accountIds));
    }

    private static BalanceForecast.Horizon horizon(BalanceForecast.Until until, Integer days, LocalDate today) {
        if (until == null || until == BalanceForecast.Until.NEXT_INCOME) {
            return new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, today,
                    today.plusDays(NEXT_INCOME_FALLBACK_DAYS), NEXT_INCOME_FALLBACK_DAYS);
        }
        if (days == null || !ALLOWED_DAYS.contains(days)) {
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
                    if (bill.withdrawalDate().isBefore(horizon.from())) {
                        throw new IllegalStateException("이용기간이 진행 중인 청구의 출금일이 지났다: " + bill.cardBillId());
                    }
                    events.add(billEvent(bill, bill.withdrawalDate(), FlowEvent.Confidence.EXPECTED));
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
    private record LivingCostRead(LivingCostEstimator.Estimate estimate, LocalDateTime syncedAt) {}
}
