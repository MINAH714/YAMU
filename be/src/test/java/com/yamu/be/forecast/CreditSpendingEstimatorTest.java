package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.forecast.CreditSpendingReader.CardDailySpending;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 마감 전 신용카드 사용 추정(S15P31E103-286). 결제일 25일·마감 일수 14일 카드는 11월 청구의 이용기간이 10/12~11/11이고
 * 출금일은 11/25다. 테스트 달력은 휴일이 없어 출금일이 결제일과 같다.
 */
class CreditSpendingEstimatorTest {
    private static final long A = 3L;
    private static final long B = 5L;
    private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);
    private static final LocalDate NOV_25 = LocalDate.of(2026, 11, 25);
    private static final CreditWithdrawalSchedule SCHEDULE = (card, spendDate) -> Optional.of(
            BillingCycle.of(card.paymentDay(), card.billingCutoffDays()).cycleFor(spendDate).paymentDate());

    @Test
    void 내일부터_마감일까지_일수에_카드_일평균을_곱해_출금일에_보수적_선_전용_출금으로_넣는다() {
        // 8·9·10월(완료 3개월) 매일 12,000원 → 일평균 12,000원. 11/7~11/11 5일 = 60,000원
        CreditSpendingEstimator.Result result = estimate(List.of(card(41L, A)), everyDay(41L, 12_000L), NOV_6,
                NOV_25);

        assertThat(result.excluded()).isEmpty();
        assertThat(result.events()).containsExactly(new FlowEvent(A, NOV_25, -60_000L,
                FlowEvent.Type.CARD_SPENDING_ESTIMATE, FlowEvent.Confidence.CONSERVATIVE,
                "카드41 · 11/7~11/11 5일 × 일평균 12,000원", 41L));
    }

    @Test
    void 마감일_당일이면_남은_날이_없어_넣지_않는다() {
        LocalDate cutoff = LocalDate.of(2026, 11, 11);

        assertThat(estimate(List.of(card(41L, A)), everyDay(41L, 12_000L), cutoff, NOV_25).events()).isEmpty();
    }

    @Test
    void 마감_다음_날이면_다음_이용기간_마감까지_세고_그_출금일이_기간_밖이면_넣지_않는다() {
        // 11/12 기준 이용기간은 11/12~12/11, 출금일 12/25. 오늘(11/12)은 빼고 11/13~12/11 29일
        LocalDate nov12 = LocalDate.of(2026, 11, 12);
        LocalDate dec25 = LocalDate.of(2026, 12, 25);

        assertThat(estimate(List.of(card(41L, A)), everyDay(41L, 1_000L), nov12, dec25).events())
                .extracting(FlowEvent::date, FlowEvent::amount, FlowEvent::title)
                .containsExactly(tuple(dec25, -29_000L, "카드41 · 11/13~12/11 29일 × 일평균 1,000원"));
        assertThat(estimate(List.of(card(41L, A)), everyDay(41L, 1_000L), nov12, dec25.minusDays(1)).events())
                .isEmpty();
    }

    @Test
    void 완료한_달이_없는_카드는_출금일이_기간_안일_때만_알린다() {
        // 이번 달(11월)에만 쓴 카드는 완료한 달이 없다
        List<CardDailySpending> thisMonth = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 11, 1); !day.isAfter(NOV_6); day = day.plusDays(1)) {
            thisMonth.add(new CardDailySpending(41L, day, 5_000L, LivingCostReader.Origin.REAL));
        }

        CreditSpendingEstimator.Result inRange = estimate(List.of(card(41L, A)), thisMonth, NOV_6, NOV_25);
        CreditSpendingEstimator.Result outOfRange = estimate(List.of(card(41L, A)), thisMonth, NOV_6,
                NOV_25.minusDays(1));

        assertThat(inRange.events()).isEmpty();
        assertThat(inRange.excluded()).containsExactly(new ExcludedItem(null, null, null, NOV_25, "카드41", null,
                ExcludedItem.Reason.CREDIT_SPENDING_UNKNOWN));
        assertThat(outOfRange.excluded()).isEmpty();
    }

    @Test
    void 여러_카드는_카드마다_자기_출금_계좌와_출금일에_넣는다() {
        UserCardRef second = new UserCardRef(42L, 1L, B, "2222", CardRegistrationStatus.INACTIVE,
                CardIssuerStatus.NORMAL, CardType.CREDIT, 14, 14);
        List<CardDailySpending> days = new ArrayList<>(everyDay(41L, 10_000L));
        days.addAll(everyDay(42L, 2_000L));

        // 결제일 14일·마감 14일: 11/6 기준 이용기간 마감 10/31은 지났고 다음 마감 11/30, 출금 12/14
        CreditSpendingEstimator.Result result = estimate(List.of(card(41L, A), second), days, NOV_6,
                LocalDate.of(2026, 12, 14));

        assertThat(result.events())
                .extracting(FlowEvent::accountId, FlowEvent::date, FlowEvent::amount, FlowEvent::refId)
                .containsExactly(tuple(A, NOV_25, -50_000L, 41L),
                        tuple(B, LocalDate.of(2026, 12, 14), -48_000L, 42L));
    }

    @Test
    void 정지_카드_체크카드_선택_밖_계좌_결제일_없는_카드와_0원은_넣지_않는다() {
        UserCardRef suspended = new UserCardRef(51L, 1L, A, "5151", CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.SUSPENDED, CardType.CREDIT, 25, 14);
        UserCardRef check = new UserCardRef(52L, 1L, A, "5252", CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, CardType.CHECK, null, null);
        UserCardRef otherAccount = new UserCardRef(53L, 1L, 99L, "5353", CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, CardType.CREDIT, 25, 14);
        UserCardRef noPaymentDay = new UserCardRef(54L, 1L, A, "5454", CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, CardType.CREDIT, null, 14);
        List<CardDailySpending> days = new ArrayList<>();
        for (long id = 51L; id <= 54L; id++) {
            days.addAll(everyDay(id, 10_000L));
        }
        days.addAll(everyDay(55L, 0L));

        CreditSpendingEstimator.Result result = estimate(
                List.of(suspended, check, otherAccount, noPaymentDay, card(55L, A)), days, NOV_6, NOV_25);

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded()).isEmpty();
    }

    private static CreditSpendingEstimator.Result estimate(List<UserCardRef> cards, List<CardDailySpending> days,
            LocalDate today, LocalDate to) {
        Map<Long, String> names = new java.util.HashMap<>();
        cards.forEach(card -> names.put(card.userCardId(), "카드" + card.userCardId()));
        return CreditSpendingEstimator.estimate(cards, names, days, Set.of(A, B), today, to, SCHEDULE);
    }

    /** 결제일 25일·마감 일수 14일 정상 신용카드. */
    private static UserCardRef card(long id, long accountId) {
        return new UserCardRef(id, 1L, accountId, "1234", CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL,
                CardType.CREDIT, 25, 14);
    }

    /** 8/1부터 11/6까지 매일 같은 금액(REAL). 완료 3개월은 8·9·10월이다. */
    private static List<CardDailySpending> everyDay(long userCardId, long amount) {
        List<CardDailySpending> days = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 8, 1); !day.isAfter(NOV_6); day = day.plusDays(1)) {
            days.add(new CardDailySpending(userCardId, day, amount, LivingCostReader.Origin.REAL));
        }
        return days;
    }
}
