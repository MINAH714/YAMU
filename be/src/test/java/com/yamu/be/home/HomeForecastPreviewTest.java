package com.yamu.be.home;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yamu.be.forecast.BalanceForecast;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HomeForecastPreviewTest {
    static final LocalDate TODAY=LocalDate.of(2026,11,13);
    static final LocalDateTime AS_OF=LocalDateTime.of(2026,11,13,3,0);

    static BalanceForecast fixture(int days, java.util.function.IntFunction<BalanceForecast.SeriesPoint> points) {
        var summary=mock(BalanceForecast.Summary.class);
        when(summary.endBalance()).thenReturn(900L);
        when(summary.minBalance()).thenReturn(-999L); // 오늘 일말 최저점은 새 요약에서 제외되어야 한다.
        var living=mock(BalanceForecast.LivingCost.class);
        when(living.projectedAmount()).thenReturn(100L);
        var series=new ArrayList<BalanceForecast.SeriesPoint>();
        for(int d=0;d<=days;d++) series.add(points.apply(d));
        return new BalanceForecast(List.of(1L),1,1000,new BalanceForecast.Horizon(BalanceForecast.Until.DAYS,TODAY,TODAY.plusDays(days),days),
                100,summary,series,List.of(),living,List.of(),
                List.of(new BalanceForecast.AccountForecast(1L,1000,AS_OF,null,List.of(),List.of(),living)),List.of(),true,AS_OF.plusSeconds(1));
    }
    static BalanceForecast.SeriesPoint point(int day,long expected,Long conservative) {
        return new BalanceForecast.SeriesPoint(TODAY.plusDays(day),-9999,expected,conservative,List.of());
    }
    @Test void excludesTodayAndSeparatesFirstRiskFromMinimumDate() {
        var input=fixture(30,d->point(d,d==0?-999:500,d==0?-999L:d==5?99L:d==20?-100L:300L));
        var r=HomeForecastPreview.from(input,1L,TODAY);
        assertThat(r.currentBalance()).isEqualTo(1000);
        assertThat(r.from()).isEqualTo(TODAY.plusDays(1));
        assertThat(r.to()).isEqualTo(TODAY.plusDays(30));
        assertThat(r.horizonDays()).isEqualTo(30);
        assertThat(r.minBalance()).isEqualTo(-100);
        assertThat(r.minBalanceDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(r.nextRiskDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(r.balanceAsOf()).isEqualTo(AS_OF);
        assertThat(r.calculatedAt()).isEqualTo(AS_OF.plusSeconds(1));
    }
    @Test void fallbackUsesExpectedAndEarliestMinimumTie() {
        var r=HomeForecastPreview.from(fixture(30,d->point(d,d==4||d==6?200:500,null)),1L,TODAY);
        assertThat(r.minBalance()).isEqualTo(200);
        assertThat(r.minBalanceDate()).isEqualTo(TODAY.plusDays(4));
        assertThat(r.nextRiskDate()).isNull();
    }
    @Test void riskIsStrictlyBelowThresholdAndUsesDisplayedLine() {
        var r=HomeForecastPreview.from(fixture(30,d->point(d,0,d==2?100L:200L)),1L,TODAY);
        assertThat(r.nextRiskDate()).isNull(); // 표시하지 않는 expected=0으로 위험을 만들지 않는다.
    }
    @Test void perDateFallbackMatchesMonthlyGraphRule() {
        var r=HomeForecastPreview.from(fixture(30,d->point(d,d==7?50:500,d==7?null:300L)),1L,TODAY);
        assertThat(r.minBalance()).isEqualTo(50);
        assertThat(r.minBalanceDate()).isEqualTo(TODAY.plusDays(7));
    }
    @Test void rejectsDifferentAccountOrShortHorizon() {
        var input=fixture(30,d->point(d,500,300L));
        assertThat(HomeForecastPreview.reusable(input,2L,TODAY)).isFalse();
        assertThat(HomeForecastPreview.reusable(input,1L,TODAY.plusDays(1))).isFalse();
        assertThatThrownBy(()->HomeForecastPreview.from(fixture(7,d->point(d,500,null)),1L,TODAY))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void missingOrDuplicateDayIsNotTreatedAsCompleteThirtyDays() {
        assertThatThrownBy(()->HomeForecastPreview.from(fixture(30,d->point(d==5?6:d,500,null)),1L,TODAY))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void noLivingCostRemainsProvisional() {
        var input=fixture(30,d->point(d,500,null));
        when(input.livingCost().projectedAmount()).thenReturn(null);
        assertThat(HomeForecastPreview.from(input,1L,TODAY).isProvisional()).isTrue();
    }
}
