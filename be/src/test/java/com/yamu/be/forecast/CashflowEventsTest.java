package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CashflowEventsTest {
    private static final Long USER = 7L;
    private static final Long A = 3L;
    private static final Long B = 5L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate TO = TODAY.plusDays(30);
    private static final Long CHECK_ON_A = 21L;
    private static final Long CREDIT_ON_B = 22L;
    private static final Long CARD_WITHOUT_ACCOUNT = 23L;
    private static final LocalDate CREDIT_WITHDRAWAL = LocalDate.of(2026, 11, 14);

    private static final Long SUSPENDED_ON_A = 24L;
    private static final Long EXPIRED_ON_A = 25L;
    private static final Long INACTIVE_ON_A = 26L;
    private static final Long SUSPENDED_CREDIT_ON_A = 27L;
    /** 결제일이 없어 출금일을 정할 수 없는 신용카드. */
    private static final Long CREDIT_WITHOUT_CYCLE_ON_A = 28L;

    private final List<LocalDate> creditSpendDates = new ArrayList<>();
    private final Map<Long, UserCardRef> registered = Map.of(
            CHECK_ON_A, card(CHECK_ON_A, A, CardType.CHECK, CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL),
            CREDIT_ON_B, new UserCardRef(CREDIT_ON_B, 1L, B, "2222", CardRegistrationStatus.ACTIVE,
                    CardIssuerStatus.NORMAL, CardType.CREDIT, 14, 13),
            CARD_WITHOUT_ACCOUNT, card(CARD_WITHOUT_ACCOUNT, null, CardType.CHECK, CardRegistrationStatus.ACTIVE,
                    CardIssuerStatus.NORMAL),
            SUSPENDED_ON_A, card(SUSPENDED_ON_A, A, CardType.CHECK, CardRegistrationStatus.ACTIVE,
                    CardIssuerStatus.SUSPENDED),
            EXPIRED_ON_A, card(EXPIRED_ON_A, A, CardType.CHECK, CardRegistrationStatus.ACTIVE,
                    CardIssuerStatus.EXPIRED),
            INACTIVE_ON_A, card(INACTIVE_ON_A, A, CardType.CHECK, CardRegistrationStatus.INACTIVE,
                    CardIssuerStatus.NORMAL),
            SUSPENDED_CREDIT_ON_A, card(SUSPENDED_CREDIT_ON_A, A, CardType.CREDIT, CardRegistrationStatus.ACTIVE,
                    CardIssuerStatus.SUSPENDED),
            CREDIT_WITHOUT_CYCLE_ON_A, card(CREDIT_WITHOUT_CYCLE_ON_A, A, CardType.CREDIT,
                    CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL));
    // BE 1 조회처럼 없거나 해제된 카드는 empty다.
    private final CardQueryService cards = mock(CardQueryService.class);
    private final CreditWithdrawalSchedule creditSchedule = (card, spendDate) -> {
        creditSpendDates.add(spendDate);
        return card.userCardId().equals(CREDIT_WITHOUT_CYCLE_ON_A) ? Optional.empty() : Optional.of(CREDIT_WITHDRAWAL);
    };

    @BeforeEach
    void setUp() {
        when(cards.findRegisteredCard(eq(USER), anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(registered.get(invocation.getArgument(1))));
    }

    @Test
    void 계좌_지정_항목은_예정일에_수입은_더하고_지출은_뺀다_오늘_지출도_뺀다() {
        CashflowEvents.Converted result = convert(Set.of(A),
                entry(1L, A, null, CashflowItem.Direction.INCOME, 2_000_000L, TODAY.plusDays(2)),
                entry(2L, A, null, CashflowItem.Direction.EXPENSE, 500_000L, TODAY));

        assertThat(result.events())
                .extracting(FlowEvent::accountId, FlowEvent::date, FlowEvent::amount, FlowEvent::type,
                        FlowEvent::confidence, FlowEvent::refId)
                .containsExactly(
                        tuple(A, TODAY.plusDays(2), 2_000_000L, FlowEvent.Type.SCHEDULED_INCOME,
                                FlowEvent.Confidence.CONFIRMED, 1L),
                        tuple(A, TODAY, -500_000L, FlowEvent.Type.SCHEDULED_OUTFLOW,
                                FlowEvent.Confidence.CONFIRMED, 2L));
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void 오늘_수입은_이미_잔액에_들어왔을_수_있어_더하지_않고_알리고_오늘_지출은_뺀다() {
        CashflowEvents.Converted result = convert(Set.of(A),
                entry(1L, A, null, CashflowItem.Direction.INCOME, 1_000_000L, TODAY),
                entry(2L, A, null, CashflowItem.Direction.EXPENSE, 500_000L, TODAY),
                entry(3L, A, null, CashflowItem.Direction.INCOME, 1_000_000L, TODAY.plusDays(1)));

        assertThat(result.events())
                .extracting(FlowEvent::refId, FlowEvent::date, FlowEvent::amount)
                .containsExactly(tuple(2L, TODAY, -500_000L), tuple(3L, TODAY.plusDays(1), 1_000_000L));
        assertThat(result.excluded())
                .extracting(ExcludedItem::cashflowItemId, ExcludedItem::reason)
                .containsExactly(tuple(1L, ExcludedItem.Reason.TODAY_INCOME_UNVERIFIED));
    }

    @Test
    void 실제_결제와_연결된_회차는_결제_데이터가_대신하므로_빼서_두_번_차감하지_않는다() {
        CashflowService.Entry paid = new CashflowService.Entry(
                item(1L, A, null, CashflowItem.Direction.EXPENSE, 500_000L), TODAY.plusDays(5), TODAY.plusDays(5), true);

        CashflowEvents.Converted result = convert(Set.of(A), paid);

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void 선택하지_않은_계좌에_귀속되는_회차는_다른_계좌로_옮기지_않고_넘긴다() {
        CashflowEvents.Converted result = convert(Set.of(A),
                entry(1L, B, null, CashflowItem.Direction.EXPENSE, 400_000L, TODAY.plusDays(3)),
                entry(2L, null, CREDIT_ON_B, CashflowItem.Direction.EXPENSE, 30_000L, TODAY.plusDays(3)));

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void 체크카드는_사용일에_신용카드는_청구_출금일에_카드_출금_계좌에서_나간다() {
        CashflowEvents.Converted result = convert(Set.of(A, B),
                entry(1L, null, CHECK_ON_A, CashflowItem.Direction.EXPENSE, 10_000L, TODAY.plusDays(1)),
                entry(2L, null, CREDIT_ON_B, CashflowItem.Direction.EXPENSE, 17_000L, TODAY.plusDays(1)));

        assertThat(result.events())
                .extracting(FlowEvent::accountId, FlowEvent::date, FlowEvent::amount)
                .containsExactly(tuple(A, TODAY.plusDays(1), -10_000L), tuple(B, CREDIT_WITHDRAWAL, -17_000L));
        assertThat(creditSpendDates).containsExactly(TODAY.plusDays(1));
    }

    @Test
    void 기간_밖으로_미뤄진_신용카드_출금은_이번_예보에_넣지_않는다() {
        CashflowEvents.Converted result = CashflowEvents.convert(USER, List.of(
                        entry(1L, null, CREDIT_ON_B, CashflowItem.Direction.EXPENSE, 17_000L, TODAY.plusDays(1))),
                Set.of(B), cards, creditSchedule, TODAY, CREDIT_WITHDRAWAL.minusDays(1));

        assertThat(result.events()).isEmpty();
    }

    @Test
    void 금액_미정과_출금_계좌를_알_수_없는_회차는_차감하지_않고_이유와_함께_알린다() {
        CashflowEvents.Converted result = convert(Set.of(A),
                entry(1L, A, null, CashflowItem.Direction.EXPENSE, null, TODAY.plusDays(2)),
                entry(2L, null, CARD_WITHOUT_ACCOUNT, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(3)),
                entry(3L, null, 99L, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(4)),
                entry(4L, null, null, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(5)),
                // 선택하지 않은 계좌의 금액 미정은 이 예보의 누락이 아니다.
                entry(5L, B, null, CashflowItem.Direction.EXPENSE, null, TODAY.plusDays(6)));

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded())
                .extracting(ExcludedItem::cashflowItemId, ExcludedItem::reason)
                .containsExactly(tuple(1L, ExcludedItem.Reason.AMOUNT_UNKNOWN),
                        tuple(2L, ExcludedItem.Reason.NO_WITHDRAWAL_ACCOUNT),
                        tuple(3L, ExcludedItem.Reason.CARD_UNAVAILABLE),
                        tuple(4L, ExcludedItem.Reason.NO_WITHDRAWAL_ACCOUNT));
    }

    @Test
    void 카드사_정지_만료_카드의_예정_지출은_차감하지_않고_이유와_함께_알린다() {
        CashflowEvents.Converted result = convert(Set.of(A),
                entry(1L, null, SUSPENDED_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(2)),
                entry(2L, null, EXPIRED_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(3)));

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded())
                .extracting(ExcludedItem::cashflowItemId, ExcludedItem::reason)
                .containsExactly(tuple(1L, ExcludedItem.Reason.CARD_SUSPENDED_OR_EXPIRED),
                        tuple(2L, ExcludedItem.Reason.CARD_SUSPENDED_OR_EXPIRED));
    }

    @Test
    void 선택하지_않은_계좌의_정지_카드_항목은_알리지_않는다() {
        // B만 예보할 때 A 계좌에 연결된 정지 카드의 예정 지출은 이 예보와 관계없다.
        CashflowEvents.Converted result = convert(Set.of(B),
                entry(1L, null, SUSPENDED_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(2)));

        assertThat(result.events()).isEmpty();
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void 정지_신용카드는_기간_안에_출금될_회차만_알리고_기간_밖_회차는_정상_카드처럼_넘긴다() {
        CashflowService.Entry spend = entry(1L, null, SUSPENDED_CREDIT_ON_A, CashflowItem.Direction.EXPENSE,
                9_000L, TODAY.plusDays(1));

        CashflowEvents.Converted inside = CashflowEvents.convert(USER, List.of(spend), Set.of(A), cards,
                creditSchedule, TODAY, CREDIT_WITHDRAWAL);
        CashflowEvents.Converted outside = CashflowEvents.convert(USER, List.of(spend), Set.of(A), cards,
                creditSchedule, TODAY, CREDIT_WITHDRAWAL.minusDays(1));

        assertThat(inside.events()).isEmpty();
        assertThat(inside.excluded())
                .extracting(ExcludedItem::cashflowItemId, ExcludedItem::reason)
                .containsExactly(tuple(1L, ExcludedItem.Reason.CARD_SUSPENDED_OR_EXPIRED));
        assertThat(outside.events()).isEmpty();
        assertThat(outside.excluded()).isEmpty();
    }

    @Test
    void 출금일을_정할_수_없는_신용카드_회차는_사용일이_기간_안이고_선택한_계좌일_때만_알린다() {
        CashflowEvents.Converted inside = convert(Set.of(A),
                entry(1L, null, CREDIT_WITHOUT_CYCLE_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(2)));
        CashflowEvents.Converted afterRange = convert(Set.of(A),
                entry(1L, null, CREDIT_WITHOUT_CYCLE_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TO.plusDays(1)));
        CashflowEvents.Converted otherAccount = convert(Set.of(B),
                entry(1L, null, CREDIT_WITHOUT_CYCLE_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(2)));

        assertThat(inside.events()).isEmpty();
        assertThat(inside.excluded())
                .extracting(ExcludedItem::cashflowItemId, ExcludedItem::date, ExcludedItem::reason)
                .containsExactly(tuple(1L, TODAY.plusDays(2), ExcludedItem.Reason.BILLING_CYCLE_UNKNOWN));
        assertThat(afterRange.excluded()).isEmpty();
        assertThat(otherAccount.excluded()).isEmpty();
    }

    @Test
    void 추천에서만_뺀_INACTIVE_카드의_예정_지출은_그대로_뺀다() {
        CashflowEvents.Converted result = convert(Set.of(A),
                entry(1L, null, INACTIVE_ON_A, CashflowItem.Direction.EXPENSE, 9_000L, TODAY.plusDays(2)));

        assertThat(result.events())
                .extracting(FlowEvent::accountId, FlowEvent::date, FlowEvent::amount)
                .containsExactly(tuple(A, TODAY.plusDays(2), -9_000L));
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void 같은_카드는_한_번만_읽는다() {
        convert(Set.of(A),
                entry(1L, null, CHECK_ON_A, CashflowItem.Direction.EXPENSE, 1_000L, TODAY.plusDays(1)),
                entry(1L, null, CHECK_ON_A, CashflowItem.Direction.EXPENSE, 1_000L, TODAY.plusDays(8)));

        verify(cards, times(1)).findRegisteredCard(USER, CHECK_ON_A);
    }

    private CashflowEvents.Converted convert(Set<Long> selected, CashflowService.Entry... entries) {
        return CashflowEvents.convert(USER, List.of(entries), selected, cards, creditSchedule, TODAY, TO);
    }

    private static UserCardRef card(Long userCardId, Long accountId, CardType type,
            CardRegistrationStatus registration, CardIssuerStatus issuer) {
        return new UserCardRef(userCardId, 1L, accountId, "1111", registration, issuer, type, null, null);
    }

    private static CashflowService.Entry entry(Long id, Long accountId, Long userCardId,
            CashflowItem.Direction direction, Long amount, LocalDate date) {
        return new CashflowService.Entry(item(id, accountId, userCardId, direction, amount), date, date, false);
    }

    private static CashflowService.Item item(Long id, Long accountId, Long userCardId,
            CashflowItem.Direction direction, Long amount) {
        return new CashflowService.Item(id, accountId, userCardId, "항목" + id, direction, amount, TODAY,
                CashflowItem.RecurrenceType.NONE, null, null, null, CashflowItem.Status.CONFIRMED, null);
    }
}
