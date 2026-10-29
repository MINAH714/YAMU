package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이후 전부 수정과 전체 삭제가 나뉜 규칙(시리즈) 전체에 맞게 적용되는지 실제 MySQL의 FK·CHECK와 함께 확인한다.
 * 지난 날짜에 시작한 규칙은 등록 API가 막으므로 SQL로 넣는다. 매주 반복의 첫 회차는 2주 전(start)이라 오늘도 회차다.
 */
@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-cashflow-series-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CashflowSeriesTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired CashflowService service;

    CashflowFixtures cashflows;
    long owner;
    long account;
    LocalDate today;
    LocalDate start;

    @BeforeEach
    void setUp() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        cashflows = new CashflowFixtures(jdbc);
        owner = fixtures.user();
        account = fixtures.account(owner);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        start = today.minusDays(14);
    }

    @Test
    void 시작한_규칙을_오늘부터_고치면_어제로_닫고_새_규칙을_잇는다() {
        long rule = cashflows.rule(owner, account, start, "WEEKLY", null);

        CashflowService.Entry result = service.update(owner, rule, fromHere(null).amount(20000L).build());

        long next = result.item().id();
        assertThat(next).isNotEqualTo(rule);
        assertThat(result.occurrenceDate()).isEqualTo(today);
        assertThat(endOf(rule)).isEqualTo(today.minusDays(1));
        assertThat(previousOf(next)).isEqualTo(rule);
        assertThat(list(start, today.plusDays(7)))
                .extracting(entry -> entry.item().id(), CashflowService.Entry::date, entry -> entry.item().amount())
                .containsExactly(tuple(rule, start, 10000L), tuple(rule, start.plusDays(7), 10000L),
                        tuple(next, today, 20000L), tuple(next, today.plusDays(7), 20000L));
    }

    @Test
    void 날짜를_고른_회차보다_앞당기면_앞당긴_날부터_바뀐다() {
        long rule = cashflows.rule(owner, account, start, "WEEKLY", null);

        CashflowService.Entry result = service.update(owner, rule,
                fromHere(today.plusDays(7)).date(today.plusDays(5)).build());

        assertThat(endOf(rule)).isEqualTo(today.plusDays(4));
        assertThat(list(today, today.plusDays(14)))
                .extracting(entry -> entry.item().id(), CashflowService.Entry::date)
                .containsExactly(tuple(rule, today), tuple(result.item().id(), today.plusDays(5)),
                        tuple(result.item().id(), today.plusDays(12)));
    }

    @Test
    void 시작_전_규칙은_제자리에서_바꾸고_따로_고친_회차를_지운다() {
        long rule = cashflows.rule(owner, account, today.plusDays(1), "WEEKLY", null);
        cashflows.occurrence(owner, account, rule, today.plusDays(8), today.plusDays(9), true);

        CashflowService.Entry result = service.update(owner, rule, fromHere(null).amount(20000L).build());

        assertThat(result.item().id()).isEqualTo(rule);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE cashflow_item_id = ?",
                Integer.class, rule)).isEqualTo(1);
        assertThat(list(today, today.plusDays(14)))
                .extracting(CashflowService.Entry::date, entry -> entry.item().amount())
                .containsExactly(tuple(today.plusDays(1), 20000L), tuple(today.plusDays(8), 20000L));
    }

    @Test
    void 이후_전부_수정은_따로_고쳐_둔_미래_회차를_새_규칙으로_덮는다() {
        long rule = cashflows.rule(owner, account, start, "WEEKLY", null);
        cashflows.occurrence(owner, account, rule, today.plusDays(7), today.plusDays(8), true);

        service.update(owner, rule, fromHere(null).amount(20000L).build());

        assertThat(list(today, today.plusDays(10)))
                .extracting(CashflowService.Entry::date, entry -> entry.item().amount())
                .containsExactly(tuple(today, 20000L), tuple(today.plusDays(7), 20000L));
    }

    @Test
    void 결제와_연결된_회차가_바꿀_범위에_있으면_거절하고_지난_연결_회차는_남긴다() {
        long rule = cashflows.rule(owner, account, start, "WEEKLY", null);
        long past = cashflows.occurrence(owner, account, rule, start.plusDays(7), start.plusDays(7), false);
        long future = cashflows.occurrence(owner, account, rule, today.plusDays(7), today.plusDays(7), false);
        cashflows.paymentMatch(owner, past);
        cashflows.paymentMatch(owner, future);

        assertPaidLocked(() -> service.update(owner, rule, fromHere(null).amount(20000L).build()));
        assertPaidLocked(() -> service.delete(owner, rule, CashflowService.DeleteScope.ALL, null));
        assertThat(endOf(rule)).isNull();

        // 미래 연결이 없으면 나뉘고, 지난 연결 회차는 기존 규칙에 그대로 남는다.
        jdbc.update("DELETE FROM cashflow_payment_match WHERE cashflow_occurrence_id = ?", future);
        service.update(owner, rule, fromHere(today.plusDays(14)).amount(20000L).build());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE cashflow_occurrence_id = ?",
                Integer.class, past)).isEqualTo(1);
    }

    @Test
    void 나뉜_시리즈를_앞_규칙에서_다시_고치면_뒤_규칙을_지우고_회차는_한_번씩만_나온다() {
        long first = cashflows.rule(owner, account, start, "WEEKLY", today.plusDays(56));
        long second = service.update(owner, first, fromHere(today.plusDays(14)).amount(20000L).build()).item().id();
        assertThat(endOf(second)).isEqualTo(today.plusDays(56));

        long third = service.update(owner, first, fromHere(today.plusDays(7)).amount(30000L).build()).item().id();

        assertThat(endOf(first)).isEqualTo(today.plusDays(6));
        assertThat(previousOf(third)).isEqualTo(first);
        // 반복을 보내지 않았으므로 시리즈 마지막 규칙의 종료일을 이어받는다.
        assertThat(endOf(third)).isEqualTo(today.plusDays(56));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE cashflow_item_id = ?",
                Integer.class, second)).isZero();
        assertThat(list(today, today.plusDays(28)))
                .extracting(CashflowService.Entry::date, entry -> entry.item().amount())
                .containsExactly(tuple(today, 10000L), tuple(today.plusDays(7), 30000L),
                        tuple(today.plusDays(14), 30000L), tuple(today.plusDays(21), 30000L),
                        tuple(today.plusDays(28), 30000L));
    }

    @Test
    void 뒤_규칙의_첫_발생일을_앞_규칙_종료일_이전으로_당기면_400이다() {
        long first = cashflows.rule(owner, account, start, "WEEKLY", null);
        long second = service.update(owner, first, fromHere(today.plusDays(14)).amount(20000L).build()).item().id();

        assertInvalid(() -> service.update(owner, second, fromHere(null).date(today.plusDays(10)).build()), "date");
    }

    @Test
    void 전체_삭제는_시리즈_전체에서_오늘_이후를_없앤다() {
        long first = cashflows.rule(owner, account, start, "WEEKLY", null);
        long second = service.update(owner, first, fromHere(today.plusDays(14)).amount(20000L).build()).item().id();

        service.delete(owner, second, CashflowService.DeleteScope.ALL, null);

        assertThat(endOf(first)).isEqualTo(today.minusDays(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE cashflow_item_id = ?",
                Integer.class, second)).isZero();
        assertThat(list(today, today.plusDays(28))).isEmpty();
        assertThat(list(start, today.minusDays(1))).extracting(CashflowService.Entry::date)
                .containsExactly(start, start.plusDays(7));
    }

    @Test
    void 오늘_시작하는_규칙은_전체_삭제하면_종료일을_바꾸지_않고_행째_지운다() {
        long rule = service.create(owner, new CashflowService.CreateCommand("헬스장", CashflowItem.Direction.EXPENSE,
                10000L, today, CashflowItem.RecurrenceType.WEEKLY, null, null, null, account, null, null, null)).id();

        service.delete(owner, rule, CashflowService.DeleteScope.ALL, null);
        em.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE cashflow_item_id = ?",
                Integer.class, rule)).isZero();
    }

    @Test
    void 지난_회차를_오늘_이후로_옮겨_둔_회차는_전체_삭제에서_뺀다() {
        long rule = cashflows.rule(owner, account, start, "WEEKLY", null);
        long moved = cashflows.occurrence(owner, account, rule, start.plusDays(7), today.plusDays(1), true);

        service.delete(owner, rule, CashflowService.DeleteScope.ALL, null);

        assertThat(list(today, today.plusDays(14))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM cashflow_occurrence WHERE cashflow_occurrence_id = ?",
                String.class, moved)).isEqualTo("EXCLUDED");
    }

    @Test
    void 지난_일정만_남은_항목은_고치거나_지울_수_없다() {
        long ended = cashflows.rule(owner, account, start, "WEEKLY", today.minusDays(1));
        long once = cashflows.rule(owner, account, today.minusDays(1), "NONE", null);

        assertInvalid(() -> service.delete(owner, ended, CashflowService.DeleteScope.ALL, null), "cashflowItemId");
        assertInvalid(() -> service.delete(owner, once, CashflowService.DeleteScope.ALL, null), "cashflowItemId");
        assertInvalid(() -> service.update(owner, ended, fromHere(null).amount(1000L).build()), "occurrenceDate");
        assertInvalid(() -> service.update(owner, once, fromHere(null).amount(1000L).build()), "occurrenceDate");
    }

    private List<CashflowService.Entry> list(LocalDate from, LocalDate to) {
        // 서비스가 같은 테스트 트랜잭션에 올려 둔 엔티티를 버리고 DB에 반영된 값을 다시 읽는다.
        em.flush();
        em.clear();
        return service.list(owner, from, to, null, null, true);
    }

    private LocalDate endOf(long rule) {
        em.flush();
        return jdbc.queryForObject("SELECT recurrence_end_date FROM cashflow_item WHERE cashflow_item_id = ?",
                LocalDate.class, rule);
    }

    private Long previousOf(long rule) {
        em.flush();
        return jdbc.queryForObject("SELECT previous_cashflow_item_id FROM cashflow_item WHERE cashflow_item_id = ?",
                Long.class, rule);
    }

    @SuppressWarnings("unchecked")
    private static void assertInvalid(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).satisfies(error -> {
            BusinessException business = (BusinessException) error;
            assertThat(business.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(((List<FieldErrorResponse>) business.getDetails().get("fieldErrors")).get(0).field())
                    .isEqualTo(field);
        });
    }

    private static void assertPaidLocked(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.PAID_OCCURRENCE_LOCKED));
    }

    private static Update fromHere(LocalDate occurrenceDate) {
        return new Update(occurrenceDate);
    }

    /** 이후 전부 수정 요청. 테스트에 필요한 필드만 채운다. */
    private static final class Update {
        private final LocalDate occurrenceDate;
        private Long amount;
        private LocalDate date;

        Update(LocalDate occurrenceDate) {
            this.occurrenceDate = occurrenceDate;
        }

        Update amount(Long value) {
            amount = value;
            return this;
        }

        Update date(LocalDate value) {
            date = value;
            return this;
        }

        CashflowService.UpdateCommand build() {
            return new CashflowService.UpdateCommand(null, null, amount, date, null, null, null, null, null,
                    CashflowService.UpdateScope.THIS_AND_FUTURE, occurrenceDate);
        }
    }
}
