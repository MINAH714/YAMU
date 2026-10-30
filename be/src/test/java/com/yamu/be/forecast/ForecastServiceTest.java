package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.forecast.AccountBalanceReader.AccountBalance;
import com.yamu.be.forecast.BalanceForecast.Until;
import com.yamu.be.forecast.CardBillReader.OutstandingBill;
import com.yamu.be.forecast.CardBillReader.Status;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForecastServiceTest {
    private static final Long USER = 7L;
    private static final Long A = 3L;
    private static final Long B = 5L;
    // UTC 10/25 15:30 = 한국 10/26 00:30. 예보는 한국 날짜 10/26부터 시작한다.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T15:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 10, 25, 15, 29);

    @Mock CashflowService cashflows;

    private final List<List<Long>> balanceRequests = new ArrayList<>();
    private final List<Boolean> refreshRequests = new ArrayList<>();
    private List<AccountBalance> balances = List.of(
            new AccountBalance(A, 800_000L, FETCHED_AT), new AccountBalance(B, 300_000L, FETCHED_AT));
    private RuntimeException balanceFailure;
    private List<OutstandingBill> bills = List.of();
    private final List<LocalDate> billRequestsTo = new ArrayList<>();
    private ForecastService service;

    @BeforeEach
    void setUp() {
        AccountBalanceReader balanceReader = (userId, accountIds, refresh) -> {
            balanceRequests.add(accountIds);
            refreshRequests.add(refresh);
            if (balanceFailure != null) {
                throw balanceFailure;
            }
            return balances;
        };
        CardBillReader billReader = (userId, accountIds, to) -> {
            billRequestsTo.add(to);
            return bills;
        };
        CardReader cardReader = (userId, cardId) -> Optional.empty();
        CreditWithdrawalSchedule creditSchedule = (card, spendDate) -> spendDate;
        service = new ForecastService(cashflows, balanceReader, billReader, cardReader, creditSchedule, CLOCK);
    }

    @Test
    void 잔액에_예정_지출과_청구_출금을_계좌별로_더한_뒤_같은_날짜끼리_합친다() {
        // 명세 예시: A 800,000원에서 11/5 예정 출금 200,000원, B 300,000원에서 11/5 카드 청구 400,000원
        givenEntries(TODAY, TODAY.plusDays(14), entry(1L, A, 200_000L, NOV_5, false));
        bills = List.of(new OutstandingBill(88L, B, Status.CONFIRMED, NOV_5, 400_000L));

        BalanceForecast result = service.forecast(USER, List.of(A, B), Until.DAYS, 14, true, false);

        assertThat(result.currentTotalBalance()).isEqualTo(1_100_000L);
        assertThat(result.series())
                .extracting(BalanceForecast.SeriesPoint::date, BalanceForecast.SeriesPoint::confirmed,
                        point -> point.events().size())
                .containsExactly(tuple(TODAY, 1_100_000L, 0), tuple(NOV_5, 500_000L, 2));
        assertThat(result.accountForecasts())
                .extracting(BalanceForecast.AccountForecast::accountId, BalanceForecast.AccountForecast::balance,
                        BalanceForecast.AccountForecast::syncedAt, forecast -> lastConfirmed(forecast.series()))
                .containsExactly(tuple(A, 800_000L, FETCHED_AT, 600_000L), tuple(B, 300_000L, FETCHED_AT, -100_000L));
    }

    @Test
    void 확정_청구는_확정_선부터_진행_중_청구는_예상_선부터_반영한다() {
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.CONFIRMED, NOV_5, 400_000L),
                new OutstandingBill(89L, A, Status.ACCUMULATING, NOV_5.plusDays(2), 50_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(result.series())
                .extracting(BalanceForecast.SeriesPoint::date, BalanceForecast.SeriesPoint::confirmed,
                        BalanceForecast.SeriesPoint::expected)
                .containsExactly(tuple(TODAY, 800_000L, 800_000L), tuple(NOV_5, 400_000L, 400_000L),
                        tuple(NOV_5.plusDays(2), 400_000L, 350_000L));
        assertThat(result.accountForecasts()).isNull();
    }

    @Test
    void 결제와_연결된_예정_지출은_빼고_청구로_한_번만_차감한다() {
        givenEntries(TODAY, TODAY.plusDays(14), entry(1L, A, 400_000L, NOV_5, true));
        bills = List.of(new OutstandingBill(88L, A, Status.CONFIRMED, NOV_5, 400_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(lastConfirmed(result.series())).isEqualTo(400_000L);
    }

    @Test
    void 월급이_들어온_뒤_조회해도_오늘_월급을_두_번_더하지_않는다() {
        balances = List.of(new AccountBalance(A, 1_000_000L, FETCHED_AT));
        CashflowService.Item salary = new CashflowService.Item(9L, A, null, "월급", CashflowItem.Direction.INCOME,
                1_000_000L, TODAY, CashflowItem.RecurrenceType.MONTHLY, null, null, CashflowItem.Status.CONFIRMED, null);
        givenEntries(TODAY, TODAY.plusDays(7), new CashflowService.Entry(salary, TODAY, TODAY, false));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 7, false, false);

        assertThat(result.series())
                .extracting(BalanceForecast.SeriesPoint::date, BalanceForecast.SeriesPoint::confirmed)
                .containsExactly(tuple(TODAY, 1_000_000L));
        assertThat(result.excluded()).extracting(ExcludedItem::reason)
                .containsExactly(ExcludedItem.Reason.TODAY_INCOME_UNVERIFIED);
    }

    @Test
    void 다른_계좌의_청구를_선택_계좌에_옮겨_담지_않는다() {
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, B, Status.CONFIRMED, NOV_5, 400_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(lastConfirmed(result.series())).isEqualTo(800_000L);
    }

    @Test
    void 출금일이_지났는데_아직_확정인_청구는_오늘_확정_선에서_뺀다() {
        // 결제일 07:00 출금 요청 뒤 결과를 기다리는 청구. 예보 시작일 이전 날짜라도 버리지 않는다.
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.CONFIRMED, TODAY.minusDays(1), 300_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(result.series())
                .extracting(BalanceForecast.SeriesPoint::date, BalanceForecast.SeriesPoint::confirmed)
                .containsExactly(tuple(TODAY, 500_000L));
        assertThat(billRequestsTo).containsExactly(TODAY.plusDays(14));
    }

    @Test
    void 미납_청구는_빼지_않고_excluded로_알린다() {
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.UNPAID, TODAY.minusDays(10), 120_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(lastConfirmed(result.series())).isEqualTo(800_000L);
        assertThat(result.excluded())
                .extracting(ExcludedItem::cardBillId, ExcludedItem::cashflowItemId, ExcludedItem::date,
                        ExcludedItem::amount, ExcludedItem::reason)
                .containsExactly(tuple(88L, null, TODAY.minusDays(10), 120_000L, ExcludedItem.Reason.UNPAID_BILL));
    }

    @Test
    void 진행_중_청구의_출금일이_지났으면_계약_위반으로_실패한다() {
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.ACCUMULATING, TODAY.minusDays(1), 50_000L));

        assertThatThrownBy(() -> service.forecast(USER, List.of(A), Until.DAYS, 14, false, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 기간은_한국_날짜_오늘부터이고_NEXT_INCOME은_설정이_없어_30일로_계산한다() {
        givenEntries(TODAY, TODAY.plusDays(30));

        BalanceForecast result = service.forecast(USER, List.of(A), null, null, false, false);

        assertThat(result.horizon()).isEqualTo(new BalanceForecast.Horizon(Until.DAYS, TODAY, TODAY.plusDays(30), 30));
        verify(cashflows).list(USER, TODAY, TODAY.plusDays(30), null, CashflowItem.Status.CONFIRMED, true);
        assertThat(result.calculatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 15, 30));
    }

    @Test
    void 중복_계좌는_한_번만_읽고_refresh를_그대로_넘긴다() {
        givenEntries(TODAY, TODAY.plusDays(7));

        BalanceForecast result = service.forecast(USER, List.of(A, B, A), Until.DAYS, 7, false, true);

        assertThat(balanceRequests).containsExactly(List.of(A, B));
        assertThat(refreshRequests).containsExactly(true);
        assertThat(result.accountIds()).containsExactly(A, B);
        assertThat(result.selectionCount()).isEqualTo(2);
    }

    @Test
    void 계좌를_고르지_않으면_ACCOUNT_SELECTION_REQUIRED다() {
        assertError(() -> service.forecast(USER, null, Until.DAYS, 7, false, false),
                ErrorCode.ACCOUNT_SELECTION_REQUIRED);
        assertError(() -> service.forecast(USER, List.of(), Until.DAYS, 7, false, false),
                ErrorCode.ACCOUNT_SELECTION_REQUIRED);
        assertThat(balanceRequests).isEmpty();
    }

    @Test
    void 잘못된_계좌_번호와_지원하지_않는_기간은_400이다() {
        assertError(() -> service.forecast(USER, Arrays.asList(A, null), Until.DAYS, 7, false, false),
                ErrorCode.INVALID_REQUEST);
        assertError(() -> service.forecast(USER, List.of(0L), Until.DAYS, 7, false, false),
                ErrorCode.INVALID_REQUEST);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 10, false, false),
                ErrorCode.INVALID_REQUEST);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, null, false, false),
                ErrorCode.INVALID_REQUEST);
        assertThat(balanceRequests).isEmpty();
    }

    @Test
    void 로그인하지_않았으면_401이다() {
        assertError(() -> service.forecast(null, List.of(A), Until.DAYS, 7, false, false), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void 선택_계좌_잔액이_하나라도_빠지면_일부만_합치지_않고_422다() {
        balances = List.of(new AccountBalance(A, 800_000L, FETCHED_AT));

        assertError(() -> service.forecast(USER, List.of(A, B), Until.DAYS, 7, false, false),
                ErrorCode.DATA_INSUFFICIENT);
        verifyNoInteractions(cashflows);
    }

    @Test
    void 남의_계좌_404와_금융망_실패는_그대로_전파하고_예정_항목을_읽지_않는다() {
        balanceFailure = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, false),
                ErrorCode.RESOURCE_NOT_FOUND);

        balanceFailure = new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, false),
                ErrorCode.FIN_API_UNAVAILABLE);

        balanceFailure = new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, false),
                ErrorCode.UPSTREAM_TIMEOUT);
        verifyNoInteractions(cashflows);
    }

    @Test
    void 금액_미정_회차는_차감하지_않고_excluded로_알린다() {
        givenEntries(TODAY, TODAY.plusDays(7), entry(1L, A, null, TODAY.plusDays(3), false));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 7, false, false);

        assertThat(lastConfirmed(result.series())).isEqualTo(800_000L);
        assertThat(result.excluded()).extracting(ExcludedItem::cashflowItemId, ExcludedItem::reason)
                .containsExactly(tuple(1L, ExcludedItem.Reason.AMOUNT_UNKNOWN));
    }

    private void givenEntries(LocalDate from, LocalDate to, CashflowService.Entry... entries) {
        when(cashflows.list(USER, from, to, null, CashflowItem.Status.CONFIRMED, true)).thenReturn(List.of(entries));
    }

    private static CashflowService.Entry entry(Long id, Long accountId, Long amount, LocalDate date, boolean paid) {
        CashflowService.Item item = new CashflowService.Item(id, accountId, null, "월세", CashflowItem.Direction.EXPENSE,
                amount, date, CashflowItem.RecurrenceType.NONE, null, null, CashflowItem.Status.CONFIRMED, null);
        return new CashflowService.Entry(item, date, date, paid);
    }

    private static long lastConfirmed(List<BalanceForecast.SeriesPoint> series) {
        return series.get(series.size() - 1).confirmed();
    }

    private static void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
