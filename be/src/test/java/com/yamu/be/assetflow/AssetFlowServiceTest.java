package com.yamu.be.assetflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.forecast.BalanceForecast;
import com.yamu.be.forecast.ForecastService;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.bank.*;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetFlowServiceTest {
    static final LocalDate TODAY = LocalDate.of(2026,11,13);
    static final YearMonth MONTH = YearMonth.of(2026,11);
    static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    LinkedAccountRepository accounts = mock(LinkedAccountRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    PersonalDataCipher cipher = mock(PersonalDataCipher.class);
    AccountBalanceService balances = mock(AccountBalanceService.class);
    ForecastService forecasts = mock(ForecastService.class);
    PersistedAssetFlowHistoryReader history = mock(PersistedAssetFlowHistoryReader.class);
    AssetFlowService service = new AssetFlowService(accounts,users,cipher,balances,forecasts,history,Clock.fixed(NOW,ZoneOffset.UTC));
    LinkedAccount account;

    static com.yamu.be.accounthistory.AccountHistoryStore.View view(BankHistory history) {
        var r=new DailyBalanceReconstructor().reconstruct(history,null);
        var days=new HashMap<LocalDate,com.yamu.be.accounthistory.AccountHistoryStore.Day>();
        r.points().stream().filter(p->p.balance()!=null).forEach(p->days.put(p.date(),
            new com.yamu.be.accounthistory.AccountHistoryStore.Day(p.balance(),com.yamu.be.accounthistory.HistorySource.SSAFY)));
        return new com.yamu.be.accounthistory.AccountHistoryStore.View(days,r.reason().name());
    }
    @BeforeEach void setup() {
        account = owned(1L);
        when(accounts.findByUserIdAndPrimaryTrue(7L)).thenReturn(List.of(account));
        AppUser user = mock(AppUser.class);
        when(user.getSsafyUserKey()).thenReturn(new byte[]{1});
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(cipher.decrypt(any(),any())).thenReturn("test-only");
        when(forecasts.preview(eq(7L),anyList(),eq(BalanceForecast.Until.DAYS),eq(30),eq(true),anyBoolean()))
                .thenAnswer(i -> forecast(i.getArgument(1),true));
        when(history.read(anyString(),any(),any(),any(),anyLong())).thenAnswer(i ->
                view(new BankHistory(i.getArgument(2),i.getArgument(3),null,1L,List.of(
                    new BankTransaction("x",i.getArgument(2),LocalTime.NOON,BankDirection.DEPOSIT,100,1000,"")),NOW)));
    }
    @Test void midnightDuringRequestIsConflictRatherThanInvalidInput() {
        Clock shifting=mock(Clock.class);
        when(shifting.instant()).thenReturn(NOW,NOW.plusSeconds(86400));
        when(accounts.findByUserIdAndPrimaryTrue(7L)).thenReturn(List.of());
        var crossing=new AssetFlowService(accounts,users,cipher,balances,forecasts,history,shifting);
        assertThatThrownBy(()->crossing.get(7L,MONTH,null,false)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORECAST_DATE_CHANGED);
    }
    @Test void forecastStartedOnDifferentDayIsConflict() {
        var crossing=new AssetFlowService(accounts,users,cipher,balances,forecasts,history,
                Clock.fixed(NOW.minusSeconds(86400),ZoneOffset.UTC));
        assertThatThrownBy(()->crossing.get(7L,MONTH,null,false)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORECAST_DATE_CHANGED);
        verifyNoInteractions(history);
    }
    LinkedAccount owned(Long id) {
        LinkedAccount a = mock(LinkedAccount.class);
        when(a.getId()).thenReturn(id);
        when(a.isConnected()).thenReturn(true);
        when(accounts.findByIdAndUserId(id,7L)).thenReturn(Optional.of(a));
        return a;
    }
    BalanceForecast forecast(List<Long> ids, boolean conservative) {
        var points = new ArrayList<BalanceForecast.SeriesPoint>();
        for(int d=0;d<=30;d++) points.add(new BalanceForecast.SeriesPoint(TODAY.plusDays(d),900,800,
                conservative?700L:null,List.of()));
        var detail = ids.stream().map(id -> new BalanceForecast.AccountForecast(id,2000,
                LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),null,List.of(),List.of(),null)).toList();
        return new BalanceForecast(ids,ids.size(),2000L*ids.size(),new BalanceForecast.Horizon(BalanceForecast.Until.DAYS,TODAY,TODAY.plusDays(30),30),
                0,null,points,List.of(),null,List.of(),detail,List.of(),true,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
    }
    @Test void splitsActualCurrentFutureAndDoesNotUseTodayEndForecast() {
        var r=service.get(7L,null,null,false);
        assertThat(r.series()).hasSize(30);
        assertThat(r.series().get(11).actualBalance()).isEqualTo(1000);
        assertThat(r.series().get(12).kind()).isEqualTo(AssetFlowResponse.Kind.CURRENT);
        assertThat(r.series().get(12).displayBalance()).isEqualTo(2000);
        assertThat(r.series().get(12).forecast()).isNull();
        assertThat(r.series().get(13).actualBalance()).isNull();
        assertThat(r.series().get(13).displayBalance()).isEqualTo(700);
        assertThat(r.balanceAsOf()).isEqualTo(NOW);
        verifyNoInteractions(balances);
    }
    @Test void fallsBackToExpectedWhenConservativeMissing() {
        when(forecasts.preview(anyLong(),anyList(),any(),anyInt(),anyBoolean(),anyBoolean())).thenReturn(forecast(List.of(1L),false));
        assertThat(service.get(7L,MONTH,null,false).series().get(13).displayBalance()).isEqualTo(800);
    }
    @Test void nextMonthStopsAtTodayPlusThirtyDays() {
        var r=service.get(7L,MONTH.plusMonths(1),null,false);
        assertThat(r.series()).hasSize(31);
        assertThat(r.series().get(12).dataStatus()).isEqualTo(AssetFlowResponse.DataStatus.AVAILABLE);
        assertThat(r.series().get(13).displayBalance()).isNull();
        assertThat(r.forecastTo()).isEqualTo(TODAY.plusDays(30));
        verifyNoInteractions(history,users);
    }
    @Test void noPrimaryDoesNotChooseFirstAccountOrCallUpstream() {
        when(accounts.findByUserIdAndPrimaryTrue(7L)).thenReturn(List.of());
        var r=service.get(7L,MONTH,null,false);
        assertThat(r.accountIds()).isEmpty();
        assertThat(r.currentTotalBalance()).isNull();
        assertThat(r.series()).allSatisfy(p->assertThat(p.displayBalance()).isNull());
        verifyNoInteractions(history,balances,forecasts,users);
    }
    @Test void checksAllOwnershipBeforeAnyExternalCall() {
        when(accounts.findByIdAndUserId(2L,7L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.get(7L,MONTH,List.of(1L,2L),false)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(history,balances,forecasts,users);
    }
    @Test void disconnectedAccountIsRejected() {
        when(account.isConnected()).thenReturn(false);
        assertThatThrownBy(()->service.get(7L,MONTH,List.of(1L),false)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(forecasts,history);
    }
    @Test void invalidMonthsAndAccountsFailBeforeUpstream() {
        for(var m:List.of(MONTH.minusMonths(12),MONTH.plusMonths(2)))
            assertThatThrownBy(()->service.get(7L,m,null,false)).isInstanceOf(BusinessException.class);
        for(var ids:List.of(List.<Long>of(),List.of(0L),List.of(-1L),java.util.stream.LongStream.rangeClosed(1,21).boxed().toList()))
            assertThatThrownBy(()->service.get(7L,MONTH,ids,false)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(forecasts,history,balances);
    }
    @Test void deduplicatesAccountIdsAndForwardsRefresh() {
        var r=service.get(7L,MONTH,List.of(1L,1L),true);
        assertThat(r.accountIds()).containsExactly(1L);
        verify(forecasts).preview(7L,List.of(1L),BalanceForecast.Until.DAYS,30,true,true);
    }
    @Test void incompleteAccountDoesNotProducePartialTotal() {
        LinkedAccount second=owned(2L);
        doReturn(view(new BankHistory(MONTH.atDay(1),TODAY.minusDays(1),null,2L,List.of(),NOW)))
                .when(history).read(anyString(),eq(second),any(),any(),anyLong());
        var r=service.get(7L,MONTH,List.of(1L,2L),false);
        assertThat(r.series().subList(0,12)).allSatisfy(p->assertThat(p.displayBalance()).isNull());
        assertThat(r.partialErrors()).extracting(AssetFlowResponse.PartialError::reason).containsExactly("HISTORY_INCOMPLETE");
        assertThat(r.series().get(12).displayBalance()).isEqualTo(4000);
    }
    @Test void externalHistoryFailureKeepsCurrentAndFuture() {
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE)).when(history).read(anyString(),any(),any(),any(),anyLong());
        var r=service.get(7L,MONTH,null,false);
        assertThat(r.series().get(0).dataStatus()).isEqualTo(AssetFlowResponse.DataStatus.ERROR);
        assertThat(r.series().get(12).displayBalance()).isEqualTo(2000);
        assertThat(r.series().get(13).displayBalance()).isEqualTo(700);
    }
    @Test void noAnchorIsNotZero() {
        doReturn(view(new BankHistory(MONTH.atDay(1),TODAY.minusDays(1),null,0L,List.of(),NOW)))
                .when(history).read(anyString(),any(),any(),any(),anyLong());
        var r=service.get(7L,MONTH,null,false);
        assertThat(r.series().get(0).displayBalance()).isNull();
        assertThat(r.partialErrors().get(0).reason()).isEqualTo("NO_ANCHOR");
    }
    @Test void unexpectedHistoryFailureIsNotHidden() {
        doThrow(new IllegalStateException("test")).when(history).read(anyString(),any(),any(),any(),anyLong());
        assertThatThrownBy(()->service.get(7L,MONTH,null,false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void pastMonthDoesNotRunForecastAndKeepsLeapFebruary() {
        when(balances.getBalances(7L,List.of(1L),false)).thenReturn(List.of(new AccountBalance(1L,2000,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC))));
        var r=service.get(7L,YearMonth.of(2026,2),null,false);
        assertThat(r.series()).hasSize(28);
        assertThat(r.series()).allSatisfy(p->assertThat(p.kind()).isEqualTo(AssetFlowResponse.Kind.ACTUAL));
        verifyNoInteractions(forecasts);
    }
    @Test void forecastFailureDoesNotReturnPartialCurrentOrReadHistory() {
        doThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT)).when(forecasts)
                .preview(anyLong(),anyList(),any(),anyInt(),anyBoolean(),anyBoolean());
        assertThatThrownBy(()->service.get(7L,MONTH,null,false)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
        verifyNoInteractions(history);
    }
    @Test void usesOldestAccountBalanceTimestampWithoutClaimingAtomicSnapshot() {
        owned(2L);
        var original=forecast(List.of(1L,2L),true);
        var detail=List.of(original.accountForecasts().get(0),new BalanceForecast.AccountForecast(2L,2000,
                LocalDateTime.ofInstant(NOW.minusSeconds(300),ZoneOffset.UTC),null,List.of(),List.of(),null));
        doReturn(new BalanceForecast(original.accountIds(),2,4000,original.horizon(),0,null,original.series(),
                List.of(),null,List.of(),detail,List.of(),true,original.calculatedAt()))
                .when(forecasts).preview(anyLong(),anyList(),any(),anyInt(),anyBoolean(),anyBoolean());
        assertThat(service.get(7L,MONTH,List.of(1L,2L),false).balanceAsOf()).isEqualTo(NOW.minusSeconds(300));
    }
}
