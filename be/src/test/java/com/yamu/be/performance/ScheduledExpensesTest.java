package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.performance.PerformanceAnalyzer.Movable;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 예정 지출을 이 카드 기준으로 나누는 규칙(S15P31E103-375). 오늘은 11월 10일, 분석하는 카드는 7번. */
class ScheduledExpensesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final long CARD = 7;
    static final long OTHER_CARD = 8;
    static final Optional<PerformancePolicyCatalog.Policy> POLICY = Optional.of(new PerformancePolicyCatalog.Policy(
            "TEST", "상품", "CREDIT", List.of("TAXI"), List.of("WATER_UTILITY")));

    @Test
    void 이_카드로_낼_회차는_예상에_더하고_실적_제외_업종은_뺀다() {
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(item(1, null, CARD, "구독", 13_000L, "STREAMING_SERVICE"), TODAY.plusDays(5), false),
                entry(item(2, null, CARD, "택시 정기", 50_000L, "TAXI"), TODAY.plusDays(5), false),
                entry(item(3, null, CARD, "오늘 낼 것", 1_000L, null), TODAY, false)), CARD, true, POLICY, TODAY);

        assertThat(split.scheduled()).extracting(PerformanceAnalyzer.Scheduled::cashflowItemId).containsExactly(1L, 3L);
        assertThat(split.movable()).isEmpty();
    }

    @Test
    void 이미_결제와_연결된_회차와_지난_회차와_금액_없는_회차는_세지_않는다() {
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(item(1, 1L, null, "통신비", 35_000L, null), TODAY.plusDays(5), true),
                entry(item(2, null, CARD, "구독", 13_000L, null), TODAY.minusDays(1), false),
                entry(item(3, 1L, null, "모름", null, null), TODAY.plusDays(5), false)), CARD, true, POLICY, TODAY);

        assertThat(split.scheduled()).isEmpty();
        assertThat(split.movable()).isEmpty();
    }

    @Test
    void 반복_항목은_한_줄로_묶어_첫_회차_날짜와_회차_합을_쓴다() {
        CashflowService.Item weekly = item(5, 1L, null, "헬스 PT", 20_000L, null);
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(weekly, TODAY.plusDays(14), false),
                entry(weekly, TODAY.plusDays(7), false)), CARD, true, POLICY, TODAY);

        assertThat(split.movable()).singleElement().satisfies(m -> {
            assertThat(m.amount()).isEqualTo(40_000);
            assertThat(m.date()).isEqualTo(TODAY.plusDays(7));
            assertThat(m.deadline()).isEqualTo(TODAY.plusDays(5));
            assertThat(m.currentMethod()).isEqualTo("ACCOUNT");
        });
    }

    @Test
    void 옮길_수_없는_사유와_마감_경계() {
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(item(1, 1L, null, "월세", 500_000L, null), TODAY.plusDays(10), false),
                entry(item(2, null, OTHER_CARD, "택시", 40_000L, "TAXI"), TODAY.plusDays(10), false),
                entry(item(3, 1L, null, "통신비", 35_000L, "TELECOMMUNICATION"), TODAY.plusDays(1), false),
                entry(item(4, 1L, null, "인터넷", 30_000L, "TELECOMMUNICATION"), TODAY.plusDays(2), false),
                entry(item(5, 1L, null, "수도", 20_000L, "WATER_UTILITY"), TODAY.plusDays(9), false),
                entry(item(6, 1L, null, "학원", 10_000L, null), TODAY.plusDays(9), false)), CARD, true, POLICY, TODAY);

        assertThat(split.movable()).extracting(Movable::cashflowItemId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(split.movable()).extracting(Movable::eligible)
                .containsExactly(false, false, false, true, true, true);
        assertThat(split.movable()).extracting(Movable::reason).containsExactly("카드로 낼 수 없는 지출이에요",
                "이 카드 실적에서 빠지는 업종이에요", "결제일이 가까워 이번 달에는 바꾸기 어려워요", null, null, null);
        assertThat(split.movable()).extracting(Movable::note).containsExactly(null, null, null,
                "결제처 반영까지 1~2일 걸려요", "이 카드 실적에 들어가는지 카드사 약관을 확인해 주세요",
                "카드로 낼 수 있는지 결제처에 확인해 주세요");
        assertThat(split.movable().get(1).currentMethod()).isEqualTo("CARD");
    }

    @Test
    void 정지된_카드로는_아무것도_옮길_수_없다() {
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(item(1, 1L, null, "통신비", 35_000L, null), TODAY.plusDays(10), false)), CARD, false, POLICY,
                TODAY);

        assertThat(split.movable()).singleElement().satisfies(m -> {
            assertThat(m.eligible()).isFalse();
            assertThat(m.reason()).isEqualTo("정지되었거나 만료된 카드로는 옮길 수 없어요");
        });
    }

    @Test
    void 정책_원본에_없는_상품이면_업종으로_거르지_않고_약관_확인을_안내한다() {
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(item(1, 1L, null, "택시", 35_000L, "TAXI"), TODAY.plusDays(10), false),
                entry(item(2, null, CARD, "택시 정기", 50_000L, "TAXI"), TODAY.plusDays(5), false)), CARD, true,
                Optional.empty(), TODAY);

        assertThat(split.scheduled()).hasSize(1);
        assertThat(split.movable()).singleElement().satisfies(m -> {
            assertThat(m.eligible()).isTrue();
            assertThat(m.note()).isEqualTo("이 카드 실적에 들어가는지 카드사 약관을 확인해 주세요");
        });
    }

    @Test
    void 금액이_같으면_날짜_항목_번호_순이다() {
        ScheduledExpenses.Split split = ScheduledExpenses.split(List.of(
                entry(item(3, 1L, null, "가", 10_000L, null), TODAY.plusDays(9), false),
                entry(item(2, 1L, null, "나", 10_000L, null), TODAY.plusDays(9), false),
                entry(item(1, 1L, null, "다", 10_000L, null), TODAY.plusDays(12), false)), CARD, true, POLICY, TODAY);

        assertThat(split.movable()).extracting(Movable::cashflowItemId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void 카드로_낼_수_없는_지출은_업종_코드나_제목으로_가른다() {
        assertThat(ScheduledExpenses.unpayable("RENT", "집")).isTrue();
        assertThat(ScheduledExpenses.unpayable(null, "11월 카드값")).isTrue();
        assertThat(ScheduledExpenses.unpayable("TELECOMMUNICATION", "통신비")).isFalse();
        assertThat(ScheduledExpenses.unpayable(null, null)).isFalse();
    }

    private static CashflowService.Item item(long id, Long accountId, Long userCardId, String title, Long amount,
            String category) {
        return new CashflowService.Item(id, accountId, userCardId, title, CashflowItem.Direction.EXPENSE, amount,
                TODAY, CashflowItem.RecurrenceType.MONTHLY, null, 10, category, CashflowItem.Status.CONFIRMED, null,
                CashflowItem.Source.MANUAL);
    }

    private static CashflowService.Entry entry(CashflowService.Item item, LocalDate date, boolean paid) {
        return new CashflowService.Entry(item, date, date, paid);
    }
}
