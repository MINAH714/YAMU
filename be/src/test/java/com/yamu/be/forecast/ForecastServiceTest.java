package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.account.AccountQueryService;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.forecast.BalanceForecast.Until;
import com.yamu.be.forecast.CardBillReader.OutstandingBill;
import com.yamu.be.forecast.CardBillReader.Status;
import com.yamu.be.forecast.LivingCostReader.DailySpending;
import com.yamu.be.forecast.LivingCostReader.Origin;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

    private static final long THRESHOLD = 100_000L;
    private static final LocalDateTime LIVING_SYNCED_AT = LocalDateTime.of(2026, 10, 25, 15, 0);
    private static final YearMonth JUL = YearMonth.of(2026, 7);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    @Mock CashflowService cashflows;
    @Mock AccountQueryService accounts;
    @Mock AccountBalanceService balanceService;
    // 따로 정하지 않은 카드는 BE 1 조회처럼 empty(Mockito 기본값)다.
    @Mock CardQueryService cardService;
    @Mock ForecastSettingsStore settingsStore;
    // 따로 정하지 않으면 다음 월급일은 없다(Mockito 기본값 Optional.empty).
    @Mock ForecastSettingsService settingsService;

    private final List<List<Long>> balanceRequests = new ArrayList<>();
    private final List<Boolean> refreshRequests = new ArrayList<>();
    private final List<List<LocalDate>> livingRequests = new ArrayList<>();
    private List<AccountBalance> balances = List.of(
            new AccountBalance(A, 800_000L, FETCHED_AT), new AccountBalance(B, 300_000L, FETCHED_AT));
    private RuntimeException balanceFailure;
    private List<OutstandingBill> bills = List.of();
    private final List<LocalDate> billRequestsTo = new ArrayList<>();
    private List<DailySpending> spending = List.of();
    private RuntimeException livingFailure;
    /** 잔고예보 설정. 기본은 명세 기본값(NEXT_INCOME·30일·안전 잔액 100,000원). */
    private ForecastSettings userSettings = ForecastSettings.DEFAULTS;
    private ForecastService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(CLOCK);
    }

    private ForecastService serviceAt(Clock clock) {
        // 생활비 상세처럼 잔액을 읽지 않는 테스트도 있어 lenient로 둔다.
        lenient().when(balanceService.getBalances(any(), anyList(), anyBoolean())).thenAnswer(invocation -> {
            balanceRequests.add(invocation.getArgument(1));
            refreshRequests.add(invocation.getArgument(2));
            if (balanceFailure != null) {
                throw balanceFailure;
            }
            return balances;
        });
        CardBillReader billReader = (userId, accountIds, to) -> {
            billRequestsTo.add(to);
            return bills;
        };
        CreditWithdrawalSchedule creditSchedule = (card, spendDate) -> Optional.of(spendDate);
        LivingCostReader livingReader = (userId, accountIds, fromDate, toDate) -> {
            livingRequests.add(List.of(fromDate, toDate));
            if (livingFailure != null) {
                throw livingFailure;
            }
            return new LivingCostReader.Spending(spending, LIVING_SYNCED_AT);
        };
        // 남의 계좌로 실패하는 테스트는 설정을 읽기 전에 끝나 lenient로 둔다.
        lenient().when(settingsStore.read(anyLong())).thenAnswer(invocation -> userSettings);
        return new ForecastService(cashflows, balanceService, billReader, cardService, creditSchedule, livingReader,
                accounts, settingsStore, settingsService, clock);
    }

    @Test
    void 잔액에_예정_지출과_청구_출금을_계좌별로_더한_뒤_같은_날짜끼리_합친다() {
        // 명세 예시: A 800,000원에서 11/5 예정 출금 200,000원, B 300,000원에서 11/5 카드 청구 400,000원
        givenEntries(TODAY, TODAY.plusDays(14), entry(1L, A, 200_000L, NOV_5, false));
        bills = List.of(new OutstandingBill(88L, B, Status.CONFIRMED, NOV_5, 400_000L));

        BalanceForecast result = service.forecast(USER, List.of(A, B), Until.DAYS, 14, true, false);

        assertThat(result.currentTotalBalance()).isEqualTo(1_100_000L);
        assertThat(result.series()).hasSize(15);
        assertThat(List.of(day(result.series(), TODAY), day(result.series(), NOV_5)))
                .extracting(BalanceForecast.SeriesPoint::date, BalanceForecast.SeriesPoint::confirmed,
                        point -> point.events().size())
                .containsExactly(tuple(TODAY, 1_100_000L, 0), tuple(NOV_5, 500_000L, 2));
        assertThat(result.accountForecasts())
                .extracting(BalanceForecast.AccountForecast::accountId, BalanceForecast.AccountForecast::balance,
                        BalanceForecast.AccountForecast::syncedAt, forecast -> lastConfirmed(forecast.series()))
                .containsExactly(tuple(A, 800_000L, FETCHED_AT, 600_000L), tuple(B, 300_000L, FETCHED_AT, -100_000L));
    }

    @Test
    void 명세_예시대로_통합은_안전해도_B_계좌가_위험하면_최종_상태는_DANGER다() {
        // A 800,000원·하루 생활비 5,000원, 11/5 예정 출금 200,000원. B 300,000원·하루 2,000원, 11/5 카드 청구 400,000원
        givenEntries(TODAY, TODAY.plusDays(14), entry(1L, A, 200_000L, NOV_5, false));
        bills = List.of(new OutstandingBill(88L, B, Status.CONFIRMED, NOV_5, 400_000L));
        spending = days(real(A, JUL, 155_000), real(A, AUG, 155_000), real(A, SEP, 150_000),
                real(B, JUL, 62_000), real(B, AUG, 62_000), real(B, SEP, 60_000));

        BalanceForecast result = service.forecast(USER, List.of(A, B), Until.DAYS, 14, true, false);

        // 11/5 통합: 확정 500,000원, 보수적 500,000 − (5,000+2,000)×10일 = 430,000원
        assertThat(day(result.series(), NOV_5))
                .extracting(BalanceForecast.SeriesPoint::confirmed, BalanceForecast.SeriesPoint::conservative)
                .containsExactly(500_000L, 430_000L);
        assertThat(result.alertThreshold()).isEqualTo(THRESHOLD);
        assertThat(result.summary().aggregateStatus()).isEqualTo(RiskDetector.Level.SAFE);
        assertThat(result.summary().status()).isEqualTo(RiskDetector.Level.DANGER);
        assertThat(result.summary().hasAccountRisk()).isTrue();
        assertThat(result.summary().atRiskAccountCount()).isEqualTo(1);
        assertThat(result.summary().largestOutflow())
                .isEqualTo(new BalanceForecast.Outflow(NOV_5, "예정 출금 합계", -600_000L));
        assertThat(result.riskWindows()).isEmpty();
        assertThat(result.livingCost())
                .extracting(BalanceForecast.LivingCost::dailyAverage, BalanceForecast.LivingCost::projectedAmount,
                        BalanceForecast.LivingCost::dataOrigin)
                .containsExactly(7_000L, 98_000L, LivingCostEstimator.DataOrigin.REAL);

        BalanceForecast.AccountForecast b = result.accountForecasts().get(1);
        assertThat(b.summary().status()).isEqualTo(RiskDetector.Level.DANGER);
        assertThat(day(b.series(), NOV_5).conservative()).isEqualTo(-120_000L);
        assertThat(b.riskWindows()).singleElement().satisfies(window -> {
            assertThat(window.from()).isEqualTo(NOV_5);
            assertThat(window.line()).isEqualTo(RiskDetector.Line.CONFIRMED);
            assertThat(window.lowestBalance()).isEqualTo(-100_000L);
            assertThat(window.shortfall()).isEqualTo(200_000L);
            assertThat(window.causes()).extracting(RiskDetector.Cause::type)
                    .containsExactly(RiskDetector.CauseType.CARD_BILLING);
        });
        assertThat(result.accountForecasts().get(0).summary().status()).isEqualTo(RiskDetector.Level.SAFE);
    }

    @Test
    void 생활비_자료가_없으면_보수적_선을_비우고_확정_예상_선으로만_판단한다() {
        givenEntries(TODAY, TODAY.plusDays(7));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 7, false, false);

        assertThat(result.series()).extracting(BalanceForecast.SeriesPoint::conservative).containsOnlyNulls();
        assertThat(result.livingCost())
                .extracting(BalanceForecast.LivingCost::projectedAmount, BalanceForecast.LivingCost::dataOrigin,
                        BalanceForecast.LivingCost::confidence)
                .containsExactly(null, LivingCostEstimator.DataOrigin.INSUFFICIENT,
                        LivingCostEstimator.Confidence.INSUFFICIENT);
        assertThat(result.summary().status()).isEqualTo(RiskDetector.Level.SAFE);
        assertThat(result.summary().minBalance()).isEqualTo(800_000L);
    }

    @Test
    void 생활비_원천_실패는_그대로_전파하고_예정_항목을_읽지_않는다() {
        livingFailure = new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);

        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, false),
                ErrorCode.FIN_API_UNAVAILABLE);
        verifyNoInteractions(cashflows);
    }

    @Test
    void 생활비는_한국_날짜_기준_완료_3개월과_이번_달을_읽는다() {
        // UTC 10/31 15:30 = 한국 11/1 00:30 → 8·9·10월이 완료 3개월, 11월이 이번 달. 8/1~11/1 일별로 읽는다.
        LocalDate nov1 = LocalDate.of(2026, 11, 1);
        ForecastService monthStart = serviceAt(Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC));
        when(cashflows.list(USER, nov1, nov1.plusDays(7), null, CashflowItem.Status.CONFIRMED, true))
                .thenReturn(List.of());

        monthStart.forecast(USER, List.of(A), Until.DAYS, 7, false, false);

        assertThat(livingRequests).containsExactly(List.of(LocalDate.of(2026, 8, 1), nov1));
    }

    @Test
    void 생활비_상세는_소유를_확인하고_계산_근거와_계좌별_값을_돌려준다() {
        spending = days(real(A, JUL, 155_000), real(A, AUG, 155_000), real(A, SEP, 150_000),
                real(B, SEP, 60_000));

        LivingCostForecast result = service.livingCost(USER, List.of(A, B, A), true);

        verify(accounts).requireConnectedAccount(USER, A);
        verify(accounts).requireConnectedAccount(USER, B);
        assertThat(result.accountIds()).containsExactly(A, B);
        assertThat(result.dailyAverage()).isEqualTo(7_000L);
        assertThat(result.projectedAmount()).isEqualTo(210_000L);
        assertThat(result.method()).isEqualTo("AVG_3M");
        assertThat(result.confidence()).isEqualTo(LivingCostEstimator.Confidence.LOW);
        assertThat(result.period()).isEqualTo(new LivingCostForecast.Period(TODAY, TODAY.plusDays(30), 30, Until.DAYS));
        assertThat(result.sources()).extracting(LivingCostForecast.Source::amount)
                .containsExactly(155_000L, 155_000L, 210_000L);
        assertThat(result.excluded()).extracting(LivingCostForecast.Exclusion::type)
                .containsExactly("RECURRING", "CREDIT_PAYMENT", "UNCLASSIFIED", "LARGE");
        assertThat(result.dailyTarget()).isNull();
        assertThat(result.dataSyncedAt()).isEqualTo(LIVING_SYNCED_AT);
        assertThat(result.accountLivingCosts())
                .extracting(LivingCostForecast.AccountLivingCost::accountId,
                        LivingCostForecast.AccountLivingCost::dailyAverage,
                        LivingCostForecast.AccountLivingCost::projectedAmount)
                .containsExactly(tuple(A, 5_000L, 150_000L), tuple(B, 2_000L, 60_000L));
    }

    @Test
    void 생활비_상세는_설정의_기본_일수와_하루_목표를_쓰고_목표를_예측에_섞지_않는다() {
        userSettings = new ForecastSettings(Until.DAYS, 14, THRESHOLD, null, 9_000L, true, true, null);
        spending = days(real(A, JUL, 155_000), real(A, AUG, 155_000), real(A, SEP, 150_000));

        LivingCostForecast result = service.livingCost(USER, List.of(A), false);

        assertThat(result.period()).isEqualTo(new LivingCostForecast.Period(TODAY, TODAY.plusDays(14), 14, Until.DAYS));
        assertThat(result.dailyAverage()).isEqualTo(5_000L);
        assertThat(result.projectedAmount()).isEqualTo(70_000L);
        assertThat(result.dailyTarget()).isEqualTo(9_000L);
        assertThat(result.accountLivingCosts()).isNull();
    }

    @Test
    void 생활비_상세는_잔고예보처럼_다음_월급일_전날까지_계산한다() {
        userSettings = new ForecastSettings(Until.NEXT_INCOME, 30, THRESHOLD, 41L, null, true, true, null);
        when(settingsService.nextIncomeDate(USER, 41L, TODAY)).thenReturn(Optional.of(NOV_5));
        spending = days(real(A, JUL, 155_000), real(A, AUG, 155_000), real(A, SEP, 150_000));

        LivingCostForecast result = service.livingCost(USER, List.of(A), false);

        // 11/5 월급이면 10/26~11/4, 9일. 잔고예보의 NEXT_INCOME 기간과 같다.
        assertThat(result.period())
                .isEqualTo(new LivingCostForecast.Period(TODAY, NOV_5.minusDays(1), 9, Until.NEXT_INCOME));
        assertThat(result.projectedAmount()).isEqualTo(45_000L);
    }

    @Test
    void 생활비_상세는_어느_계좌에도_자료가_없으면_0원으로_확정하지_않고_422다() {
        assertError(() -> service.livingCost(USER, List.of(A), false), ErrorCode.DATA_INSUFFICIENT);
    }

    @Test
    void 생활비_상세에서_남의_계좌는_404이고_원천을_읽지_않는다() {
        when(accounts.requireConnectedAccount(USER, A)).thenReturn(null);
        when(accounts.requireConnectedAccount(USER, B)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertError(() -> service.livingCost(USER, List.of(A, B), false), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(livingRequests).isEmpty();
    }

    @Test
    void 확정_청구는_확정_선부터_진행_중_청구는_예상_선부터_반영한다() {
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.CONFIRMED, NOV_5, 400_000L),
                new OutstandingBill(89L, A, Status.ACCUMULATING, NOV_5.plusDays(2), 50_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(List.of(day(result.series(), TODAY), day(result.series(), NOV_5),
                        day(result.series(), NOV_5.plusDays(2))))
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
                1_000_000L, TODAY, CashflowItem.RecurrenceType.MONTHLY, null, TODAY.getDayOfMonth(), null,
                CashflowItem.Status.CONFIRMED, null);
        givenEntries(TODAY, TODAY.plusDays(7), new CashflowService.Entry(salary, TODAY, TODAY, false));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 7, false, false);

        assertThat(result.series()).extracting(BalanceForecast.SeriesPoint::confirmed).containsOnly(1_000_000L);
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
    void 정지된_카드의_예정_지출은_빼지만_그_카드의_확정_청구는_그대로_뺀다() {
        // 확정 청구는 카드가 정지돼도 출금된다. 정지·만료 판단은 앞으로의 예정 카드 지출에만 한다.
        Long suspendedCard = 30L;
        when(cardService.findRegisteredCard(USER, suspendedCard)).thenReturn(Optional.of(new UserCardRef(
                suspendedCard, 1L, A, "3030", CardRegistrationStatus.ACTIVE, CardIssuerStatus.SUSPENDED,
                CardType.CHECK, null, null)));
        CashflowService.Item subscription = new CashflowService.Item(9L, null, suspendedCard, "구독",
                CashflowItem.Direction.EXPENSE, 20_000L, NOV_5, CashflowItem.RecurrenceType.NONE, null, null, null,
                CashflowItem.Status.CONFIRMED, null);
        givenEntries(TODAY, TODAY.plusDays(14), new CashflowService.Entry(subscription, NOV_5, NOV_5, false));
        bills = List.of(new OutstandingBill(88L, A, Status.CONFIRMED, NOV_5, 400_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(day(result.series(), NOV_5).confirmed()).isEqualTo(400_000L);
        assertThat(result.excluded()).extracting(ExcludedItem::cashflowItemId, ExcludedItem::reason)
                .containsExactly(tuple(9L, ExcludedItem.Reason.CARD_SUSPENDED_OR_EXPIRED));
    }

    @Test
    void 출금일이_지났는데_아직_확정인_청구는_오늘_확정_선에서_뺀다() {
        // 결제일 07:00 출금 요청 뒤 결과를 기다리는 청구. 예보 시작일 이전 날짜라도 버리지 않는다.
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.CONFIRMED, TODAY.minusDays(1), 300_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(day(result.series(), TODAY).confirmed()).isEqualTo(500_000L);
        assertThat(lastConfirmed(result.series())).isEqualTo(500_000L);
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
    void 출금_계좌_상태_출금일이_빈_청구는_계약_위반으로_실패한다() {
        givenEntries(TODAY, TODAY.plusDays(14));
        for (OutstandingBill broken : List.of(new OutstandingBill(88L, null, Status.CONFIRMED, NOV_5, 1_000L),
                new OutstandingBill(89L, A, null, NOV_5, 1_000L),
                new OutstandingBill(90L, A, Status.CONFIRMED, null, 1_000L))) {
            bills = List.of(broken);

            assertThatThrownBy(() -> service.forecast(USER, List.of(A), Until.DAYS, 14, false, false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 출금일이_지났는데_진행_중인_청구는_오늘_빠질_돈으로_본다() {
        // 청구 확정·출금 배치가 없어 출금일이 지나도 진행 중으로 남는다(2026-11-04 BE 2 합의)
        givenEntries(TODAY, TODAY.plusDays(14));
        bills = List.of(new OutstandingBill(88L, A, Status.ACCUMULATING, TODAY.minusDays(1), 50_000L));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.DAYS, 14, false, false);

        assertThat(day(result.series(), TODAY).expected()).isEqualTo(750_000L);
        assertThat(day(result.series(), TODAY).confirmed()).isEqualTo(800_000L);
        assertThat(result.excluded()).isEmpty();
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
        // 기간 기준이 NEXT_INCOME이어도(보냈든 설정이든) 보낸 days는 검사한다
        assertError(() -> service.forecast(USER, List.of(A), Until.NEXT_INCOME, 5, false, false),
                ErrorCode.INVALID_REQUEST);
        assertError(() -> service.forecast(USER, List.of(A), null, 5, false, false), ErrorCode.INVALID_REQUEST);
        assertThat(balanceRequests).isEmpty();
    }

    @Test
    void 계좌는_중복을_빼고_20개까지만_고를_수_있다() {
        List<Long> twentyOne = new ArrayList<>();
        for (long id = 1; id <= 21; id++) {
            twentyOne.add(id);
        }

        assertError(() -> service.forecast(USER, twentyOne, Until.DAYS, 7, false, false), ErrorCode.INVALID_REQUEST);
        assertThat(balanceRequests).isEmpty();

        // 중복을 빼면 2개라 통과한다
        List<Long> duplicated = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            duplicated.add(i % 2 == 0 ? A : B);
        }
        givenEntries(TODAY, TODAY.plusDays(7));
        assertThat(service.forecast(USER, duplicated, Until.DAYS, 7, false, false).accountIds()).containsExactly(A, B);
    }

    @Test
    void NEXT_INCOME은_다음_월급일_전날까지이고_월급일이_내일이면_오늘_하루다() {
        userSettings = new ForecastSettings(Until.NEXT_INCOME, 30, THRESHOLD, 55L, null, true, true, null);
        service = serviceAt(CLOCK);
        givenEntries(TODAY, TODAY.plusDays(9));
        when(settingsService.nextIncomeDate(USER, 55L, TODAY)).thenReturn(Optional.of(TODAY.plusDays(10)));

        BalanceForecast result = service.forecast(USER, List.of(A), null, null, false, false);

        assertThat(result.horizon()).isEqualTo(new BalanceForecast.Horizon(Until.NEXT_INCOME, TODAY,
                TODAY.plusDays(9), 9));
        assertThat(result.series()).hasSize(10);

        givenEntries(TODAY, TODAY);
        when(settingsService.nextIncomeDate(USER, 55L, TODAY)).thenReturn(Optional.of(TODAY.plusDays(1)));
        BalanceForecast eve = service.forecast(USER, List.of(A), Until.NEXT_INCOME, null, false, false);

        assertThat(eve.horizon()).isEqualTo(new BalanceForecast.Horizon(Until.NEXT_INCOME, TODAY, TODAY, 0));
        assertThat(eve.series()).hasSize(1);
    }

    @Test
    void 다음_월급일을_모르면_NEXT_INCOME도_30일_DAYS로_계산한다() {
        givenEntries(TODAY, TODAY.plusDays(30));

        BalanceForecast result = service.forecast(USER, List.of(A), Until.NEXT_INCOME, null, false, false);

        assertThat(result.horizon()).isEqualTo(new BalanceForecast.Horizon(Until.DAYS, TODAY, TODAY.plusDays(30),
                30));
    }

    @Test
    void 기간과_안전_잔액을_보내지_않으면_잔고예보_설정의_값을_쓴다() {
        userSettings = new ForecastSettings(Until.DAYS, 14, 50_000L, null, null, true, true, null);
        service = serviceAt(CLOCK);
        givenEntries(TODAY, TODAY.plusDays(14));

        BalanceForecast byDefault = service.forecast(USER, List.of(A), null, null, false, false);
        BalanceForecast daysOnly = service.forecast(USER, List.of(A), Until.DAYS, null, false, false);

        assertThat(byDefault.horizon().days()).isEqualTo(14);
        assertThat(byDefault.alertThreshold()).isEqualTo(50_000L);
        assertThat(daysOnly.horizon().days()).isEqualTo(14);
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
    void 잔액_조회_오류는_0원으로_채우지_않고_그대로_전파하며_예정_항목을_읽지_않는다() {
        balanceFailure = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, false),
                ErrorCode.RESOURCE_NOT_FOUND);

        // SSAFY 회원 아님(400)과 새로고침 한도(429)도 BE 1이 던진 그대로다.
        balanceFailure = new BusinessException(ErrorCode.INVALID_REQUEST);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, false),
                ErrorCode.INVALID_REQUEST);
        balanceFailure = new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        assertError(() -> service.forecast(USER, List.of(A), Until.DAYS, 7, false, true),
                ErrorCode.TOO_MANY_REQUESTS);

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
                amount, date, CashflowItem.RecurrenceType.NONE, null, null, null, CashflowItem.Status.CONFIRMED, null);
        return new CashflowService.Entry(item, date, date, paid);
    }

    @SafeVarargs
    private static List<DailySpending> days(List<DailySpending>... months) {
        return Arrays.stream(months).flatMap(List::stream).toList();
    }

    /** 다 확인한 지난달의 모든 날 줄. 금액은 첫날에 몰고 나머지 날은 0원이다. */
    private static List<DailySpending> real(Long accountId, YearMonth month, long amount) {
        List<DailySpending> rows = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            long dayAmount = day == 1 ? amount : 0;
            rows.add(new DailySpending(accountId, month.atDay(day), dayAmount, dayAmount, Origin.REAL, true));
        }
        return rows;
    }

    private static BalanceForecast.SeriesPoint day(List<BalanceForecast.SeriesPoint> series, LocalDate date) {
        return series.stream().filter(point -> point.date().equals(date)).findFirst().orElseThrow();
    }

    private static long lastConfirmed(List<BalanceForecast.SeriesPoint> series) {
        return series.get(series.size() - 1).confirmed();
    }

    private static void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
