package com.yamu.be.home;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.yamu.be.card.CardManagementService;
import com.yamu.be.card.dto.CardListResponse;
import com.yamu.be.forecast.*;
import com.yamu.be.global.exception.*;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.performance.*;
import com.yamu.be.report.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HomeHighlightsTest {
    CardManagementService cards=mock(CardManagementService.class);
    ForecastService forecasts=mock(ForecastService.class);
    ForecastSettingsService settings=mock(ForecastSettingsService.class);
    ReportService reports=mock(ReportService.class);
    PerformanceService performances=mock(PerformanceService.class);
    HomeReader reader=mock(HomeReader.class);
    AuthUser me=new AuthUser(7L,1L);
    HomeService home=new HomeService(cards,forecasts,settings,reports,performances,reader,
            Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"),ZoneOffset.UTC));
    ForecastSettingsResponse config=mock(ForecastSettingsResponse.class);

    @BeforeEach void setup() {
        when(cards.list(me,null)).thenReturn(new CardListResponse(List.of(),null));
        when(performances.overview(7L,null,false)).thenReturn(new CardPerformancesResponse("2026-11",17,List.of(),null));
        when(settings.get(7L)).thenReturn(config);
        when(config.baseAccountId()).thenReturn(1L);
        var monthly=mock(MonthlyReportResponse.class);
        when(monthly.yearMonth()).thenReturn("2026-11");
        when(monthly.benefitSummary()).thenReturn(new MonthlyReportResponse.BenefitSummary(1700,1000));
        when(reports.monthly(7L,null)).thenReturn(monthly);
        when(reports.benefits(7L,YearMonth.of(2026,11),BenefitSource.YAMU)).thenReturn(new BenefitReportResponse("2026-11","PARTIAL",
                new BenefitReportResponse.Summary(1500,1000,0,0,500,1,List.of()),List.of(),List.of(),BenefitSource.YAMU,List.of()));
        when(forecasts.preview(eq(7L),anyList(),any(),any(),eq(true),eq(false))).thenAnswer(i->
                HomeForecastPreviewTest.fixture(30,d->HomeForecastPreviewTest.point(d,500,300L)));
    }
    @Test void usesExplicitCurrentMonthYamuBenefitsAndReusesThirtyDayForecast() {
        var r=home.home(me);
        assertThat(r.partialErrors()).isEmpty();
        assertThat(r.monthSummary().totalBenefit()).isEqualTo(1700); // 기존 ALL 필드는 유지
        assertThat(r.benefitSummary().totalBenefit()).isEqualTo(1500);
        assertThat(r.benefitSummary().confirmedBenefit()).isEqualTo(1000);
        assertThat(r.benefitSummary().unconfirmedBenefit()).isEqualTo(500);
        assertThat(r.benefitSummary().unconvertedBenefitCount()).isEqualTo(1);
        assertThat(r.benefitSummary().source()).isEqualTo(BenefitSource.YAMU);
        assertThat(r.forecastPreview().minBalance()).isEqualTo(300);
        verify(forecasts,times(1)).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
    }
    @Test void sevenDayLegacyRemainsSevenDaysButNewPreviewRequestsThirty() {
        doAnswer(i->HomeForecastPreviewTest.fixture(i.getArgument(3)==null?7:30,d->HomeForecastPreviewTest.point(d,500,300L)))
                .when(forecasts).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
        var r=home.home(me);
        assertThat(r.balanceForecast().horizonDays()).isEqualTo(7);
        assertThat(r.forecastPreview().horizonDays()).isEqualTo(30);
        verify(forecasts).preview(7L,List.of(1L),BalanceForecast.Until.DAYS,30,true,false);
    }
    @Test void previewFailurePreservesLegacyAndBenefits() {
        doAnswer(i->{
            if(i.getArgument(3)!=null) throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);
            return HomeForecastPreviewTest.fixture(7,d->HomeForecastPreviewTest.point(d,500,null));
        }).when(forecasts).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
        var r=home.home(me);
        assertThat(r.forecastPreview()).isNull();
        assertThat(r.balanceForecast()).isNotNull();
        assertThat(r.benefitSummary()).isNotNull();
        assertThat(r.partialErrors()).containsExactly(new HomeResponse.PartialError("forecastPreview","UPSTREAM_TIMEOUT"));
    }
    @Test void legacyFailureCanStillReturnIndependentThirtyDayPreview() {
        doAnswer(i->{
            if(i.getArgument(3)==null) throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            return HomeForecastPreviewTest.fixture(30,d->HomeForecastPreviewTest.point(d,500,300L));
        }).when(forecasts).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
        var r=home.home(me);
        assertThat(r.balanceForecast()).isNull();
        assertThat(r.dataSyncedAt()).isNull();
        assertThat(r.forecastPreview().balanceAsOf()).isEqualTo(HomeForecastPreviewTest.AS_OF);
        assertThat(r.benefitSummary()).isNotNull();
        assertThat(r.partialErrors()).containsExactly(new HomeResponse.PartialError("balanceForecast","INTERNAL_ERROR"));
    }
    @Test void reportFailureDoesNotFabricateZeroAndKeepsForecast() {
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(reports).benefits(anyLong(),any(),any());
        var r=home.home(me);
        assertThat(r.benefitSummary()).isNull();
        assertThat(r.monthSummary()).isNotNull();
        assertThat(r.forecastPreview()).isNotNull();
        assertThat(r.partialErrors()).containsExactly(new HomeResponse.PartialError("benefitSummary","INTERNAL_ERROR"));
    }
    @Test void noBaseAccountHasNoForecastErrorAndDoesNotCallBankForecast() {
        when(config.baseAccountId()).thenReturn(null);
        var r=home.home(me);
        assertThat(r.forecastPreview()).isNull();
        assertThat(r.balanceForecast()).isNull();
        assertThat(r.benefitSummary()).isNotNull();
        assertThat(r.partialErrors()).isEmpty();
        verifyNoInteractions(forecasts);
    }
    @Test void externalFailureIsSharedWithoutRepeatingFinanceLookup() {
        for (ErrorCode code: List.of(ErrorCode.UPSTREAM_TIMEOUT, ErrorCode.FIN_API_ERROR, ErrorCode.FIN_API_UNAVAILABLE)) {
            clearInvocations(forecasts);
            doThrow(new BusinessException(code)).when(forecasts).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
            var r=home.home(me);
            assertThat(r.balanceForecast()).isNull();
            assertThat(r.forecastPreview()).isNull();
            assertThat(r.benefitSummary()).isNotNull();
            assertThat(r.partialErrors()).containsExactly(new HomeResponse.PartialError("balanceForecast",code.name()),
                    new HomeResponse.PartialError("forecastPreview",code.name()));
            verify(forecasts,times(1)).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
        }
    }
    @Test void nextIncomeWithActualThirtyDaysDoesNotTriggerAnotherCalculation() {
        doReturn(HomeForecastPreviewTest.fixture(30,d->HomeForecastPreviewTest.point(d,500,null),BalanceForecast.Until.NEXT_INCOME))
                .when(forecasts).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
        assertThat(home.home(me).forecastPreview()).isNotNull();
        verify(forecasts,times(1)).preview(anyLong(),anyList(),any(),any(),anyBoolean(),anyBoolean());
    }
    @Test void benefitMonthUsesKoreanDateRatherThanUtcDate() {
        var endOfMonth=new HomeService(cards,forecasts,settings,reports,performances,reader,
                Clock.fixed(Instant.parse("2026-11-30T15:01:00Z"),ZoneOffset.UTC));
        when(config.baseAccountId()).thenReturn(null);
        when(reports.benefits(7L,YearMonth.of(2026,12),BenefitSource.YAMU)).thenReturn(new BenefitReportResponse("2026-12","PARTIAL",
                new BenefitReportResponse.Summary(0,0,0,0,0,0,List.of()),List.of(),List.of(),BenefitSource.YAMU,List.of()));
        assertThat(endOfMonth.home(me).benefitSummary().yearMonth()).isEqualTo("2026-12");
        verify(reports).benefits(7L,YearMonth.of(2026,12),BenefitSource.YAMU);
    }
}
