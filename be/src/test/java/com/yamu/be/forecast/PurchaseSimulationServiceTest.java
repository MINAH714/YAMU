package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.merchant.MerchantCatalogService;
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
    @Mock MerchantCatalogService catalog;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TAXI", "CITY_BUS", "INTERCITY_BUS", "EXPRESS_BUS",
            "SUBWAY", "RAILWAY", "ELECTRIC_UTILITY", "WATER_UTILITY", "CITY_GAS_UTILITY", "UNIVERSITY",
            "PRIMARY_SECONDARY_SCHOOL", "KINDERGARTEN", "ACADEMY", "DRIVING_SCHOOL"})
    void excludedCategoryIsRejectedBeforeReadsOrCalculation(String category) {
        var input = request();
        input.setMerchantCategoryCode(category);
        var service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills, catalog);
        assertThatThrownBy(() -> service.simulate(USER, input)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e.getDetails()).isEqualTo(Map.of("fieldErrors", List.of(new FieldErrorResponse(
                    "merchantCategoryCode", "이 업종은 모의 구매를 지원하지 않아요. 다른 업종을 선택해 주세요."))));
        });
        org.mockito.Mockito.verifyNoInteractions(forecasts, cards, recommender, creditLimits, bills, catalog);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ELECTRIC_UTILITY", "UNIVERSITY", "TAXI"})
    void merchantOnlyCannotBypassExcludedIndustry(String category) {
        var input = request();
        input.setUserCardId(null);
        input.setMerchantId(55L);
        when(forecasts.today()).thenReturn(NOV_5);
        when(catalog.resolveSelection(55L, null)).thenReturn(new MerchantCatalogService.Selection(55L, category));
        var service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills, catalog);
        assertThatThrownBy(() -> service.simulate(USER, input)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        org.mockito.Mockito.verifyNoInteractions(recommender, creditLimits, bills);
        verify(forecasts, never()).prepare(any(), anyList(), any(), any(), anyBoolean(), any());
    }

    @Test
    void 오늘은_요청마다_한_번만_정해_구매일_검사와_예보_시작일에_같이_쓴다() {
        // 구매일을 검사한 뒤 자정이 지나 시계가 11/6이 되어도, 예보는 검사한 날(11/5)부터 계산해야 한다
        when(forecasts.today()).thenReturn(NOV_5, NOV_5.plusDays(1));
        when(cards.requireRegisteredCard(USER, CARD)).thenReturn(new UserCardRef(CARD, 1L, ACCOUNT, "1234",
                CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL, CardType.CHECK, null, null));
        when(forecasts.prepare(eq(USER), anyList(), isNull(), isNull(), anyBoolean(), eq(NOV_5)))
                .thenReturn(new ForecastService.Prepared(List.of(ACCOUNT),
                        new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, NOV_5, NOV_5.plusDays(7), 7),
                        100_000L, null, null, List.of(), List.of(), Map.of(ACCOUNT, 1_000_000L), Map.of(), List.of(),
                        List.of()));
        PurchaseSimulationService service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills, catalog);

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
        when(calendar.businessDayOnOrAfter(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(creditLimits.check(USER, CARD, 300_000L)).thenReturn(CreditLimitSource.LimitCheck.exceeded(100_000L));
        when(forecasts.prepare(eq(USER), anyList(), isNull(), isNull(), anyBoolean(), eq(NOV_5)))
                .thenReturn(new ForecastService.Prepared(List.of(ACCOUNT),
                        new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, NOV_5, NOV_5.plusDays(30), 30),
                        100_000L, null, null, List.of(), List.of(), Map.of(ACCOUNT, 1_000_000L), Map.of(), List.of(),
                        List.of()));
        PurchaseSimulationService service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills, catalog);

        SimulationResponse result = service.simulate(USER, request());

        assertThat(result.creditLimit()).isEqualTo(new SimulationResponse.CreditLimit("EXCEEDED", 100_000L));
    }

    @Test
    void 전액_즉시할인이어도_출금_계좌가_없는_카드는_422_CARD_ACCOUNT_UNKNOWN이다() {
        // 추천 계산 뒤 카드의 연결 계좌가 풀린 경우. 출금이 없다고 계산기까지 가서 500이 나면 안 된다(Codex 검토)
        when(forecasts.today()).thenReturn(NOV_5);
        when(cards.requireRegisteredCard(USER, CARD)).thenReturn(new UserCardRef(CARD, 1L, null, "1234",
                CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL, CardType.CHECK, null, null));
        when(catalog.resolveSelection(null, "SUPERMARKET"))
                .thenReturn(new MerchantCatalogService.Selection(null, "SUPERMARKET"));
        when(recommender.recommend(any())).thenReturn(SimulationRecommender.Result.of(
                new SimulationRecommender.Recommendation(CARD,
                        new SimulationRecommender.Benefit(new BigDecimal("300000"), List.of(), 300_000L),
                        List.of(), null, List.of(), List.of())));
        PurchaseSimulationService service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills, catalog);
        SimulationRequest request = request();
        request.setMerchantCategoryCode("SUPERMARKET");

        assertThatThrownBy(() -> service.simulate(USER, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DATA_INSUFFICIENT);
                    assertThat(e.getDetails()).isEqualTo(Map.of("reason", "CARD_ACCOUNT_UNKNOWN"));
                });
        verify(forecasts, never()).prepare(any(), anyList(), any(), any(), anyBoolean(), any());
    }

    @Test
    void 오늘이_2월_29일이면_구매일_상한은_다음_해_2월_28일이고_3월_1일은_400이다() {
        // plusYears(1)은 없는 날(2029-02-29) 대신 그달 말일로 맞춘다. 상한이 3/1로 밀리지 않는지 확인한다(!173 리뷰)
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        LocalDate lastDay = LocalDate.of(2029, 2, 28);
        when(forecasts.today()).thenReturn(leapDay);
        when(cards.requireRegisteredCard(USER, CARD)).thenReturn(new UserCardRef(CARD, 1L, ACCOUNT, "1234",
                CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL, CardType.CHECK, null, null));
        when(forecasts.prepare(eq(USER), anyList(), isNull(), isNull(), anyBoolean(), eq(leapDay)))
                .thenReturn(new ForecastService.Prepared(List.of(ACCOUNT),
                        new BalanceForecast.Horizon(BalanceForecast.Until.DAYS, leapDay, leapDay.plusDays(7), 7),
                        100_000L, null, null, List.of(), List.of(), Map.of(ACCOUNT, 1_000_000L), Map.of(), List.of(),
                        List.of()));
        PurchaseSimulationService service = new PurchaseSimulationService(forecasts, cards, cardNames, recommender,
                calendar, creditLimits, bills, catalog);
        SimulationRequest lastDayRequest = request();
        lastDayRequest.setPurchaseDate(lastDay);
        SimulationRequest nextDayRequest = request();
        nextDayRequest.setPurchaseDate(lastDay.plusDays(1));

        assertThat(service.simulate(USER, lastDayRequest).input().purchaseDate()).isEqualTo(lastDay);
        assertThatThrownBy(() -> service.simulate(USER, nextDayRequest))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(e.getDetails()).isEqualTo(Map.of("fieldErrors",
                            List.of(new FieldErrorResponse("purchaseDate", "구매일은 오늘부터 1년 안으로 골라 주세요."))));
                });
    }

    private static SimulationRequest request() {
        SimulationRequest request = new SimulationRequest();
        request.setAccountIds(List.of(ACCOUNT));
        request.setUserCardId(CARD);
        request.setAmount(BigDecimal.valueOf(300_000));
        request.setProductCategoryCode("PRODUCT_ELECTRONICS");
        request.setPaymentChannel("OFFLINE");
        return request;
    }
}
