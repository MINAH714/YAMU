package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.forecast.CreditSpendingReader.CardDailySpending;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 마감 전 신용카드 사용 추정(S15P31E103-286, 체크 생활비와 같은 규칙은 -405). 결제일 25일·마감 일수 14일 카드는 11월 청구의
 * 이용기간이 10/12~11/11이고 출금일은 11/25다. 테스트 달력은 휴일이 없어 출금일이 결제일과 같다. AI 서버가 없으면 TSB이고, 매일 같은
 * 금액을 90일 넘게 쓰면 TSB 하루 예측은 그 금액이다.
 */
class CreditSpendingEstimatorTest {
    private static final long A = 3L;
    private static final long B = 5L;
    private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);
    private static final LocalDate NOV_25 = LocalDate.of(2026, 11, 25);
    private static final CreditWithdrawalSchedule SCHEDULE = (card, spendDate) -> Optional.of(
            BillingCycle.of(card.paymentDay(), card.billingCutoffDays()).cycleFor(spendDate).paymentDate());

    @Test
    void 내일부터_마감일까지_일수에_하루_예상을_곱해_출금일에_보수적_선_전용_출금으로_넣는다() {
        // 8/1부터 매일 12,000원 → TSB 하루 12,000원. 11/7~11/11 5일 = 60,000원(오늘 쓴 12,000원은 진행 중 청구서에 있음)
        CreditSpendingEstimator.Result result = estimate(List.of(card(41L, A)), everyDay(41L, 12_000L), NOV_6,
                NOV_25);

        assertThat(result.excluded()).isEmpty();
        assertThat(result.beyond()).isEmpty();
        assertThat(result.events()).containsExactly(new FlowEvent(A, NOV_25, -60_000L,
                FlowEvent.Type.CARD_SPENDING_ESTIMATE, FlowEvent.Confidence.CONSERVATIVE,
                "카드41 · 11/7~11/11 5일 × 하루 예상 12,000원", 41L));
        assertThat(result.predictions()).containsExactly(new CreditSpendingEstimator.Prediction(41L, 5,
                new LivingCostProjector.AccountProjection(41L, 60_000L, 12_000L, "TSB",
                        LivingCostEstimator.Confidence.HIGH, LivingCostEstimator.DataOrigin.REAL), null));
    }

    @Test
    void 마감일_당일이면_남은_날이_없어_넣지_않는다() {
        LocalDate cutoff = LocalDate.of(2026, 11, 11);

        assertThat(estimate(List.of(card(41L, A)), everyDay(41L, 12_000L), cutoff, NOV_25).events()).isEmpty();
    }

    @Test
    void 마감_다음_날이면_다음_이용기간_마감까지_세고_그_출금일이_기간_밖이면_선_대신_기간_밖_미리보기로_돌려준다() {
        // 11/12 기준 이용기간은 11/12~12/11, 출금일 12/25. 오늘(11/12)은 빼고 11/13~12/11 29일.
        // 원천은 출처 기간의 모든 날에 줄을 주므로 오늘(11/12)까지 매일 1,000원을 넣는다(-405부터 최근 90일로 예측)
        LocalDate nov12 = LocalDate.of(2026, 11, 12);
        List<CardDailySpending> untilNov12 = everyDay(41L, 1_000L, nov12);
        LocalDate dec25 = LocalDate.of(2026, 12, 25);

        assertThat(estimate(List.of(card(41L, A)), untilNov12, nov12, dec25).events())
                .extracting(FlowEvent::date, FlowEvent::amount, FlowEvent::title)
                .containsExactly(tuple(dec25, -29_000L, "카드41 · 11/13~12/11 29일 × 하루 예상 1,000원"));
        CreditSpendingEstimator.Result beyond = estimate(List.of(card(41L, A)), untilNov12, nov12,
                dec25.minusDays(1));
        assertThat(beyond.events()).isEmpty();
        // 점검 R-5(-348): 전에는 안내 없이 버렸다. 선에는 넣지 않고 같은 출금을 기간 밖 미리보기로 돌려준다.
        assertThat(beyond.beyond()).containsExactly(new FlowEvent(A, dec25, -29_000L,
                FlowEvent.Type.CARD_SPENDING_ESTIMATE, FlowEvent.Confidence.CONSERVATIVE,
                "카드41 · 11/13~12/11 29일 × 하루 예상 1,000원", 41L));
        assertThat(beyond.excluded()).isEmpty();
    }

    @Test
    void 신용_생활_소비_기록이_없는_카드는_출금일이_기간_안일_때만_알리고_이력에_남기지_않는다() {
        CreditSpendingEstimator.Result inRange = estimate(List.of(card(41L, A)), List.of(), NOV_6, NOV_25);
        CreditSpendingEstimator.Result outOfRange = estimate(List.of(card(41L, A)), List.of(), NOV_6,
                NOV_25.minusDays(1));

        assertThat(inRange.events()).isEmpty();
        assertThat(inRange.excluded()).containsExactly(new ExcludedItem(null, null, null, NOV_25, "카드41", null,
                ExcludedItem.Reason.CREDIT_SPENDING_UNKNOWN));
        assertThat(inRange.predictions()).isEmpty();
        assertThat(outOfRange.excluded()).isEmpty();
        assertThat(outOfRange.beyond()).isEmpty();
    }

    @Test
    void 기록이_14일_미만이면_AI를_부르지_않고_TSB로_추정한다() {
        // 11/1~11/6만 매일 5,000원(어제까지 기록 5일). 전에는 완료한 달이 없어 "모름"이었다(-286). 이제 체크 생활비처럼 TSB
        // TSB 하루 = 5,000 × (1 − 0.8^5) = 3,361.6원 → 6일 20,170원 − 오늘 몫 3,362원 = 16,808원 ÷ 5 = 하루 3,362원
        List<CardDailySpending> thisMonth = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 11, 1); !day.isAfter(NOV_6); day = day.plusDays(1)) {
            thisMonth.add(new CardDailySpending(41L, day, 5_000L, LivingCostReader.Origin.REAL));
        }
        FakeAi ai = new FakeAi(Map.of(7, 1L));

        CreditSpendingEstimator.Result result = estimate(List.of(card(41L, A)), thisMonth, NOV_6, NOV_25, ai);

        assertThat(ai.calls).isEmpty();
        assertThat(result.excluded()).isEmpty();
        assertThat(result.events()).extracting(FlowEvent::amount, FlowEvent::title)
                .containsExactly(tuple(-16_810L, "카드41 · 11/7~11/11 5일 × 하루 예상 3,362원"));
        assertThat(result.predictions()).extracting(p -> p.projection().method(), p -> p.projection().confidence())
                .containsExactly(tuple("TSB", LivingCostEstimator.Confidence.LOW));
    }

    @Test
    void 기록이_14일_이상이면_신용_API를_카드_번호_대신_임의_key로_부르고_오늘_몫까지만_뺀다() {
        // R = 5일이면 오늘을 넣은 6일 값이 필요해 7일 예측만 부른다. 7일 70,000원 → 6일 60,000원.
        // 오늘 12,000원을 썼지만 오늘 몫(10,000원)까지만 빼 50,000원 ÷ 5 = 하루 10,000원
        FakeAi ai = new FakeAi(Map.of(7, 70_000L));

        CreditSpendingEstimator.Result result = estimate(List.of(card(41L, A)), everyDay(41L, 12_000L), NOV_6,
                NOV_25, ai);

        assertThat(ai.calls).extracting(Call::endpoint, Call::asOf, Call::horizon)
                .containsExactly(tuple(AiLivingCostClient.Endpoint.CREDIT_SPENDING, NOV_6.minusDays(1), 7));
        assertThat(ai.calls.get(0).keys()).containsExactly("a0");
        assertThat(result.events()).extracting(FlowEvent::amount, FlowEvent::title)
                .containsExactly(tuple(-50_000L, "카드41 · 11/7~11/11 5일 × 하루 예상 10,000원"));
        assertThat(result.predictions()).extracting(p -> p.projection().method(),
                CreditSpendingEstimator.Prediction::modelVersion).containsExactly(tuple("ML_V1", "credit-v1"));
    }

    @Test
    void 남은_일수가_같은_카드는_한_번에_부르고_다르면_따로_부른다() {
        // 41·43: 결제일 25일(R = 5일) → 7일 예측 한 번에 두 key. 42: 결제일 14일(마감 11/30, R = 24일) → 7·14·30일 세 번
        UserCardRef second = new UserCardRef(42L, 1L, B, "2222", CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, CardType.CREDIT, 14, 14);
        List<CardDailySpending> days = new ArrayList<>(everyDay(41L, 10_000L));
        days.addAll(everyDay(42L, 2_000L));
        days.addAll(everyDay(43L, 3_000L));
        FakeAi ai = new FakeAi(Map.of(7, 7_000L, 14, 14_000L, 30, 30_000L));

        estimate(List.of(card(41L, A), second, card(43L, A)), days, NOV_6, LocalDate.of(2026, 12, 14), ai);

        assertThat(ai.calls).extracting(Call::horizon, call -> call.keys().size())
                .containsExactly(tuple(7, 2), tuple(7, 1), tuple(14, 1), tuple(30, 1));
    }

    @Test
    void AI가_실패하면_그_묶음은_TSB로_추정한다() {
        FakeAi ai = new FakeAi(Map.of());

        CreditSpendingEstimator.Result result = estimate(List.of(card(41L, A)), everyDay(41L, 12_000L), NOV_6,
                NOV_25, ai);

        assertThat(ai.calls).hasSize(1);
        assertThat(result.events()).extracting(FlowEvent::amount).containsExactly(-60_000L);
        assertThat(result.predictions()).extracting(p -> p.projection().method(),
                CreditSpendingEstimator.Prediction::modelVersion).containsExactly(tuple("TSB", null));
    }

    @Test
    void 기록_60일_미만이면_30일_점을_TSB_아래로_내리지_않는다() {
        // 10/8부터 매일 2,000원(어제까지 기록 29일, MEDIUM). 결제일 14일 카드는 R = 24일(11/7~11/30)이라 7·14·30일 점을 잇는다.
        // AI 30일이 1,000원으로 작아도 30일 점은 TSB × 30 = 2,000 × (1 − 0.8^29) × 30 = 59,907원 → 25일 값 45,561원
        // − 오늘 몫 1,822원 = 43,739원 ÷ 24 = 하루 1,822원 → 43,728원. 하한이 없으면 30일 점이 14일 점(14,000원)으로 올라 13,440원이다
        UserCardRef card = new UserCardRef(42L, 1L, A, "2222", CardRegistrationStatus.ACTIVE,
                CardIssuerStatus.NORMAL, CardType.CREDIT, 14, 14);
        List<CardDailySpending> days = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 10, 8); !day.isAfter(NOV_6); day = day.plusDays(1)) {
            days.add(new CardDailySpending(42L, day, 2_000L, LivingCostReader.Origin.REAL));
        }
        FakeAi ai = new FakeAi(Map.of(7, 7_000L, 14, 14_000L, 30, 1_000L));

        CreditSpendingEstimator.Result result = estimate(List.of(card), days, NOV_6, LocalDate.of(2026, 12, 14), ai);

        assertThat(result.predictions()).extracting(CreditSpendingEstimator.Prediction::remainingDays,
                p -> p.projection().method(), p -> p.projection().confidence(), p -> p.projection().projectedAmount())
                .containsExactly(tuple(24, "ML_V1", LivingCostEstimator.Confidence.MEDIUM, 43_728L));
    }

    @Test
    void 원천이_음수_오늘_뒤_날짜_같은_카드_날짜_출처_두_줄을_주면_숨기지_않고_던진다() {
        CardDailySpending ok = new CardDailySpending(41L, NOV_6, 1_000L, LivingCostReader.Origin.REAL);
        List<List<CardDailySpending>> broken = List.of(
                List.of(new CardDailySpending(41L, NOV_6, -1L, LivingCostReader.Origin.REAL)),
                List.of(new CardDailySpending(41L, NOV_6.plusDays(1), 1_000L, LivingCostReader.Origin.REAL)),
                List.of(ok, ok));
        for (List<CardDailySpending> days : broken) {
            assertThatThrownBy(() -> estimate(List.of(card(41L, A)), days, NOV_6, NOV_25))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 여러_카드는_카드마다_자기_출금_계좌와_출금일에_넣는다() {
        UserCardRef second = new UserCardRef(42L, 1L, B, "2222", CardRegistrationStatus.ACTIVE,
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
    void 끈_카드_정지_카드_체크카드_선택_밖_계좌_결제일_없는_카드와_0원은_넣지_않는다() {
        // 추천에서 끈 카드(INACTIVE)는 결제 승인이 CARD_INACTIVE로 거절한다
        UserCardRef inactive = new UserCardRef(56L, 1L, A, "5656", CardRegistrationStatus.INACTIVE,
                CardIssuerStatus.NORMAL, CardType.CREDIT, 25, 14);
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
        days.addAll(everyDay(56L, 10_000L));

        CreditSpendingEstimator.Result result = estimate(
                List.of(suspended, check, otherAccount, noPaymentDay, card(55L, A), inactive), days, NOV_6, NOV_25);

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded()).isEmpty();
        assertThat(result.beyond()).isEmpty();
        // 0원으로 예측한 카드(55)는 출금에 넣지 않지만 예측 이력에는 남긴다
        assertThat(result.predictions()).extracting(CreditSpendingEstimator.Prediction::userCardId,
                p -> p.projection().projectedAmount()).containsExactly(tuple(55L, 0L));
    }

    private static CreditSpendingEstimator.Result estimate(List<UserCardRef> cards, List<CardDailySpending> days,
            LocalDate today, LocalDate to) {
        return estimate(cards, days, today, to, AiLivingCostClient.disabled());
    }

    private static CreditSpendingEstimator.Result estimate(List<UserCardRef> cards, List<CardDailySpending> days,
            LocalDate today, LocalDate to, AiLivingCostClient ai) {
        Map<Long, String> names = new HashMap<>();
        cards.forEach(card -> names.put(card.userCardId(), "카드" + card.userCardId()));
        return CreditSpendingEstimator.estimate(cards, names, days, Set.of(A, B), today, to, SCHEDULE,
                new LivingCostProjector(ai));
    }

    private record Call(AiLivingCostClient.Endpoint endpoint, LocalDate asOf, int horizon, List<String> keys) {}

    /** 기간마다 정한 값을 모든 key에 답하는 AI. 값이 없는 기간은 실패(empty). */
    private static final class FakeAi extends AiLivingCostClient {
        private final Map<Integer, Long> answers;
        private final List<Call> calls = new ArrayList<>();

        FakeAi(Map<Integer, Long> answers) {
            super(null, Clock.systemUTC(), Duration.ZERO);
            this.answers = answers;
        }

        @Override
        boolean enabled() {
            return true;
        }

        @Override
        Optional<Answer> predict(Endpoint endpoint, LocalDate asOf, int horizonDays, Map<String, long[]> histories) {
            calls.add(new Call(endpoint, asOf, horizonDays, List.copyOf(histories.keySet())));
            Long answer = answers.get(horizonDays);
            if (answer == null) {
                return Optional.empty();
            }
            Map<String, Long> out = new HashMap<>();
            histories.keySet().forEach(key -> out.put(key, answer));
            return Optional.of(new Answer("credit-v1", out));
        }
    }

    /** 결제일 25일·마감 일수 14일 정상 신용카드. */
    private static UserCardRef card(long id, long accountId) {
        return new UserCardRef(id, 1L, accountId, "1234", CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL,
                CardType.CREDIT, 25, 14);
    }

    /** 8/1부터 11/6까지 매일 같은 금액(REAL). 어제까지 기록이 90일을 넘는다. */
    private static List<CardDailySpending> everyDay(long userCardId, long amount) {
        return everyDay(userCardId, amount, NOV_6);
    }

    /** 8/1부터 last까지 매일 같은 금액(REAL). */
    private static List<CardDailySpending> everyDay(long userCardId, long amount, LocalDate last) {
        List<CardDailySpending> days = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 8, 1); !day.isAfter(last); day = day.plusDays(1)) {
            days.add(new CardDailySpending(userCardId, day, amount, LivingCostReader.Origin.REAL));
        }
        return days;
    }
}
