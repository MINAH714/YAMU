package com.yamu.be.forecast;

import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 선택 계좌의 날짜별 잔고예보를 만든다. 계좌마다 현재 잔액에 예정 수입·지출과 카드 청구 출금을 더한 뒤 같은 날짜끼리 합한다.
 *
 * <p>잔액(BE 1)·카드(BE 1)·청구(BE 2) 원천 구현이 아직 없어 Spring 빈으로 등록하지 않는다(등록하면 서버가 뜨지 않는다).
 * 원천이 붙는 MR에서 {@code @Service}와 컨트롤러를 더한다.
 */
public class ForecastService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 14, 30);
    /** 다음 수입일을 정할 잔고예보 설정이 아직 없어 명세의 대체 기간을 쓴다. */
    private static final int NEXT_INCOME_FALLBACK_DAYS = 30;

    private final CashflowService cashflows;
    private final AccountBalanceReader balances;
    private final CardBillReader bills;
    private final CardReader cards;
    private final CreditWithdrawalSchedule creditSchedule;
    private final Clock clock;

    ForecastService(CashflowService cashflows, AccountBalanceReader balances, CardBillReader bills,
            CardReader cards, CreditWithdrawalSchedule creditSchedule, Clock clock) {
        this.cashflows = cashflows;
        this.balances = balances;
        this.bills = bills;
        this.cards = cards;
        this.creditSchedule = creditSchedule;
        this.clock = clock;
    }

    /**
     * 선택 계좌의 통합 예보와, 자세히 보기면 계좌별 예보를 돌려준다.
     * 잔액을 하나라도 못 읽으면 일부만 합친 예보를 주지 않고 실패한다(FR-033).
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
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        List<Long> selected = selectedAccounts(accountIds);
        BalanceForecast.Horizon horizon = horizon(until, days, LocalDate.now(clock.withZone(KOREA)));

        // 잔액은 금융망을 부를 수 있어 DB를 읽기 전에 따로 읽는다. 남의·연결 해제 계좌는 BE 1이 404로 거절한다.
        Map<Long, AccountBalanceReader.AccountBalance> balanceById = readBalances(authenticatedUserId, selected, refresh);
        List<CashflowService.Entry> entries = cashflows.list(authenticatedUserId, horizon.from(), horizon.to(),
                null, CashflowItem.Status.CONFIRMED, true);
        Set<Long> selectedSet = Set.copyOf(selected);
        CashflowEvents.Converted converted = CashflowEvents.convert(authenticatedUserId, entries, selectedSet, cards,
                creditSchedule, horizon.from(), horizon.to());
        List<FlowEvent> events = new ArrayList<>(converted.events());
        events.addAll(billEvents(authenticatedUserId, selected, selectedSet, horizon));

        Map<Long, Long> startBalances = new LinkedHashMap<>();
        selected.forEach(accountId -> startBalances.put(accountId, balanceById.get(accountId).balance()));
        ForecastCalculator.Projection projection = ForecastCalculator.project(startBalances, events,
                horizon.from(), horizon.to());

        List<BalanceForecast.AccountForecast> accountForecasts = null;
        if (includeAccountDetails) {
            accountForecasts = selected.stream()
                    .map(accountId -> new BalanceForecast.AccountForecast(accountId,
                            balanceById.get(accountId).balance(), balanceById.get(accountId).fetchedAt(),
                            series(projection.byAccount().get(accountId))))
                    .toList();
        }
        long currentTotal = startBalances.values().stream().reduce(0L, Math::addExact);
        return new BalanceForecast(selected, selected.size(), currentTotal, horizon, series(projection.total()),
                accountForecasts, converted.excluded(), true, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
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

    private Map<Long, AccountBalanceReader.AccountBalance> readBalances(Long userId, List<Long> selected,
            boolean refresh) {
        Map<Long, AccountBalanceReader.AccountBalance> byId = new HashMap<>();
        for (AccountBalanceReader.AccountBalance balance : balances.getBalances(userId, selected, refresh)) {
            byId.put(balance.linkedAccountId(), balance);
        }
        for (Long accountId : selected) {
            if (!byId.containsKey(accountId)) {
                throw new BusinessException(ErrorCode.DATA_INSUFFICIENT);
            }
        }
        return byId;
    }

    /** 청구 출금을 출금 계좌의 출금일에 뺀다. 확정 청구는 확정 선부터, 진행 중 청구는 예상 선부터 반영한다. */
    private List<FlowEvent> billEvents(Long userId, List<Long> selected, Set<Long> selectedSet,
            BalanceForecast.Horizon horizon) {
        List<FlowEvent> events = new ArrayList<>();
        for (CardBillReader.BillWithdrawal bill : bills.upcoming(userId, selected, horizon.from(), horizon.to())) {
            if (bill.amount() < 0) {
                throw new IllegalStateException("청구 출금액이 음수다: " + bill.cardBillId());
            }
            // 계약상 오지 않지만, 다른 계좌의 청구를 선택 계좌에 옮겨 담지 않는다.
            if (!selectedSet.contains(bill.accountId())) {
                continue;
            }
            events.add(new FlowEvent(bill.accountId(), bill.withdrawalDate(), -bill.amount(),
                    FlowEvent.Type.CARD_BILLING,
                    bill.confirmed() ? FlowEvent.Confidence.CONFIRMED : FlowEvent.Confidence.EXPECTED,
                    "카드 청구 출금", bill.cardBillId()));
        }
        return events;
    }

    private static List<BalanceForecast.SeriesPoint> series(List<ForecastCalculator.BalancePoint> points) {
        return points.stream()
                .map(point -> new BalanceForecast.SeriesPoint(point.date(), point.confirmed(), point.expected(),
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
}
