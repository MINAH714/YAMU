package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.approve.CreditLimitSource;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBillRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 시뮬레이션 서비스의 날짜 기준(DB 없음). 예보 재료·카드·추천은 가짜다. */
@ExtendWith(MockitoExtension.class)
class PurchaseSimulationServiceTest {
    private static final long USER = 7L;
    private static final long ACCOUNT = 3L;
    private static final long CARD = 11L;
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    @Mock ForecastService forecasts;
    @Mock CardQueryService cards;
    @Mock CardNameReader cardNames;
    @Mock SimulationRecommender recommender;
    @Mock BusinessDayCalendar calendar;
    @Mock CreditLimitSource creditLimits;
    @Mock CardBillRepository bills;

    @Test
    void 오늘은_요청마다_한_번만_정해_구매일_검사와_예보_시작일에_같이_쓴다() {
        // 구매일을 검사한 뒤 자정이 지나 시계가 11/6이 되어도, 예보는 검사한 날(11/5)부터 계산해야 한다
        when(forecasts.today()).thenReturn(NOV_5, NOV_5.plusDays(1));
        when(cards.requireRegisteredCard(USER, CARD)).thenReturn(new UserCardRef(CARD, 1L, ACCOUNT, "1234",
                CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL, CardType.CHECK, null, null));
        when(recommender.recommend(any())).thenReturn(Optional.empty());
        when(forecasts.prepare(eq(USER), anyList(), isNull(), isNull(), anyBoolean(), eq(NOV_5)))
                .thenReturn(new ForecastService.Prepared(List.of(ACCOUNT),
                        new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, NOV_5, NOV_5.plusDays(7), 7),
                        100_000L, null, null, List.of(), List.of(), Map.of(ACCOUNT, 1_000_000L), Map.of()));
        PurchaseSimulationService service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills);

        SimulationResponse result = service.simulate(USER, request());

        verify(forecasts, times(1)).today();
        assertThat(result.input().purchaseDate()).isEqualTo(NOV_5);
        assertThat(result.schedule()).singleElement()
                .satisfies(withdrawal -> assertThat(withdrawal.date()).isEqualTo(NOV_5));
        assertThat(result.comparison().after().endBalance()).isEqualTo(700_000L);
    }

    @Test
    void 신용카드_한도는_결제와_같은_한도_포트의_결과를_그대로_옮긴다() {
        // 한도 구현이 생기면 시뮬레이션도 따라 바뀌어야 한다(!126 리뷰). 여기서는 한도 초과로 답하는 가짜 포트를 쓴다
        when(forecasts.today()).thenReturn(NOV_5);
        when(cards.requireRegisteredCard(USER, CARD)).thenReturn(new UserCardRef(CARD, 1L, ACCOUNT, "1234",
                CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL, CardType.CREDIT, 25, null));
        when(bills.existsByUserCardIdAndStatus(CARD, BillStatus.UNPAID)).thenReturn(false);
        when(recommender.recommend(any())).thenReturn(Optional.empty());
        when(calendar.businessDayOnOrAfter(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(creditLimits.check(USER, CARD, 300_000L)).thenReturn(CreditLimitSource.LimitCheck.exceeded(100_000L));
        when(forecasts.prepare(eq(USER), anyList(), isNull(), isNull(), anyBoolean(), eq(NOV_5)))
                .thenReturn(new ForecastService.Prepared(List.of(ACCOUNT),
                        new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, NOV_5, NOV_5.plusDays(30), 30),
                        100_000L, null, null, List.of(), List.of(), Map.of(ACCOUNT, 1_000_000L), Map.of()));
        PurchaseSimulationService service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills);

        SimulationResponse result = service.simulate(USER, request());

        assertThat(result.creditLimit()).isEqualTo(new SimulationResponse.CreditLimit("EXCEEDED", 100_000L));
    }

    private static SimulationRequest request() {
        SimulationRequest request = new SimulationRequest();
        request.setAccountIds(List.of(ACCOUNT));
        request.setUserCardId(CARD);
        request.setAmount(BigDecimal.valueOf(300_000));
        request.setProductCategoryCode("PRODUCT_ELECTRONICS");
        return request;
    }
}
