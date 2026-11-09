package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL에서 예정 규칙 조회 쿼리와 등록 저장이 스키마 제약과 맞는지 확인한다. */
@Transactional
// The inherited container restarts between test classes. Do not reuse another class's DataSource.
@TestPropertySource(properties = "spring.application.name=yamu-cashflow-repository-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CashflowItemRepositoryTest extends MySqlTestSupport {
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    @Autowired JdbcTemplate jdbc;
    @Autowired CashflowItemRepository items;
    @Autowired CashflowService service;

    OwnershipFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
    }

    @Test
    void 조회_기간과_겹치는_본인_규칙만_돌려준다() {
        long owner = fixtures.user();
        long inside = cashflow(owner, "EXPENSE", FROM.plusDays(1), "NONE", null, "CONFIRMED");
        cashflow(owner, "EXPENSE", FROM.minusDays(1), "NONE", null, "CONFIRMED");
        cashflow(owner, "EXPENSE", TO.plusDays(1), "NONE", null, "CONFIRMED");
        long ongoing = cashflow(owner, "EXPENSE", FROM.minusMonths(2), "MONTHLY", null, "CONFIRMED");
        long endsOnFrom = cashflow(owner, "EXPENSE", FROM.minusMonths(2), "MONTHLY", FROM, "CONFIRMED");
        cashflow(owner, "EXPENSE", FROM.minusMonths(2), "MONTHLY", FROM.minusDays(1), "CONFIRMED");
        cashflow(owner, "EXPENSE", TO.plusDays(1), "WEEKLY", null, "CONFIRMED");
        cashflow(fixtures.user(), "EXPENSE", FROM.plusDays(1), "NONE", null, "CONFIRMED");

        assertThat(idsOf(items.findRulesForUser(owner, FROM, TO, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                null, CashflowItem.Status.CONFIRMED))).containsExactly(ongoing, endsOnFrom, inside);
    }

    @Test
    void 기간_경계일의_1회_항목도_포함한다() {
        long owner = fixtures.user();
        long first = cashflow(owner, "EXPENSE", FROM, "NONE", null, "CONFIRMED");
        long last = cashflow(owner, "EXPENSE", TO, "NONE", null, "CONFIRMED");

        assertThat(idsOf(items.findRulesForUser(owner, FROM, TO, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                null, CashflowItem.Status.CONFIRMED))).containsExactly(first, last);
    }

    @Test
    void 유형과_상태로_거른다() {
        long owner = fixtures.user();
        long expense = cashflow(owner, "EXPENSE", FROM.plusDays(1), "NONE", null, "CONFIRMED");
        long income = cashflow(owner, "INCOME", FROM.plusDays(2), "NONE", null, "CONFIRMED");
        long candidate = cashflow(owner, "EXPENSE", FROM.plusDays(3), "NONE", null, "CANDIDATE");

        assertThat(idsOf(items.findRulesForUser(owner, FROM, TO, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                null, CashflowItem.Status.CONFIRMED))).containsExactly(expense, income);
        assertThat(idsOf(items.findRulesForUser(owner, FROM, TO, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                CashflowItem.Direction.INCOME, CashflowItem.Status.CONFIRMED))).containsExactly(income);
        assertThat(idsOf(items.findRulesForUser(owner, FROM, TO, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                null, CashflowItem.Status.CANDIDATE))).containsExactly(candidate);
    }

    @Test
    void 등록하면_규칙과_첫_발생건이_스키마_제약을_통과해_저장된다() {
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        long card = fixtures.card(owner, fixtures.product(), account, "ACTIVE");
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

        CashflowService.Item byAccount = service.create(owner, new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 500000L, tomorrow, CashflowItem.RecurrenceType.MONTHLY,
                null, null, tomorrow.plusMonths(6), account, null, "H01-RENT", null));
        CashflowService.Item byCard = service.create(owner, new CashflowService.CreateCommand("헬스장",
                CashflowItem.Direction.EXPENSE, 89000L, tomorrow, CashflowItem.RecurrenceType.NONE,
                null, null, null, null, card, null, null));

        assertThat(jdbc.queryForMap("""
                SELECT linked_account_id, user_card_id, item_kind, status, version
                FROM cashflow_item WHERE cashflow_item_id = ?
                """, byAccount.id()))
                .containsEntry("linked_account_id", account)
                .containsEntry("item_kind", "SCHEDULED")
                .containsEntry("status", "CONFIRMED")
                .containsEntry("version", 0L);
        assertThat(jdbc.queryForObject("SELECT user_card_id FROM cashflow_item WHERE cashflow_item_id = ?",
                Long.class, byCard.id())).isEqualTo(card);
        assertThat(jdbc.queryForList("""
                SELECT occurrence_date FROM cashflow_occurrence WHERE cashflow_item_id IN (?, ?)
                """, LocalDate.class, byAccount.id(), byCard.id())).containsOnly(tomorrow);
    }

    @Test
    void 다른_사용자의_계좌로는_등록되지_않는다() {
        long owner = fixtures.user();
        long strangerAccount = fixtures.account(fixtures.user());
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

        assertThatThrownBy(() -> service.create(owner, new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 500000L, tomorrow, CashflowItem.RecurrenceType.NONE,
                null, null, null, strangerAccount, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ?",
                Integer.class, owner)).isZero();
    }

    @Test
    void 보유_카드_목록에_보이는_ACTIVE와_INACTIVE_카드만_결제_카드로_등록된다() {
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        long product = fixtures.product();
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

        for (String status : List.of("ACTIVE", "INACTIVE")) {
            long card = fixtures.card(owner, product, account, status);
            assertThat(service.create(owner, cardExpense(tomorrow, card)).userCardId()).isEqualTo(card);
        }
        for (String status : List.of("UNREGISTERED", "PENDING")) {
            long card = fixtures.card(owner, product, account, status);
            assertThatThrownBy(() -> service.create(owner, cardExpense(tomorrow, card)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    @Test
    void 카드사_정지_만료_카드는_등록되지_않는다() {
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        long product = fixtures.product();
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

        for (String issuerStatus : List.of("SUSPENDED", "EXPIRED")) {
            long card = fixtures.card(owner, product, account, "ACTIVE");
            jdbc.update("UPDATE user_card SET issuer_status = ? WHERE user_card_id = ?", issuerStatus, card);
            assertThatThrownBy(() -> service.create(owner, cardExpense(tomorrow, card)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ?",
                Integer.class, owner)).isZero();
    }

    private static CashflowService.CreateCommand cardExpense(LocalDate date, long card) {
        return new CashflowService.CreateCommand("구독", CashflowItem.Direction.EXPENSE, 10000L, date,
                CashflowItem.RecurrenceType.NONE, null, null, null, null, card, null, null);
    }

    @Test
    void 매월_기준일은_첫_발생일과_맞아야_하고_매월에만_둔다() {
        long owner = fixtures.user();
        // 31일 기준이면 11월은 말일 30일에 시작할 수 있다.
        long monthEnd = cashflow(owner, "EXPENSE", LocalDate.of(2026, 11, 30), "MONTHLY", null, "CONFIRMED");
        jdbc.update("UPDATE cashflow_item SET recurrence_day_of_month = 31 WHERE cashflow_item_id = ?", monthEnd);
        assertThat(jdbc.queryForObject("SELECT recurrence_day_of_month FROM cashflow_item WHERE cashflow_item_id = ?",
                Integer.class, monthEnd)).isEqualTo(31);

        long midMonth = cashflow(owner, "EXPENSE", LocalDate.of(2026, 11, 15), "MONTHLY", null, "CONFIRMED");
        long weekly = cashflow(owner, "EXPENSE", LocalDate.of(2026, 11, 15), "WEEKLY", null, "CONFIRMED");
        for (Object[] invalid : List.of(new Object[] {31, midMonth}, new Object[] {32, monthEnd},
                new Object[] {15, weekly})) {
            // MySQL CHECK 위반(3819)은 Spring이 분류하지 않을 수 있어 오류 번호로 확인한다.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE cashflow_item SET recurrence_day_of_month = ? WHERE cashflow_item_id = ?", invalid))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .satisfies(failure -> assertThat(((java.sql.SQLException)
                            ((org.springframework.dao.DataAccessException) failure).getMostSpecificCause())
                            .getErrorCode()).isEqualTo(3819));
        }
    }

    private static List<Long> idsOf(List<CashflowItem> rules) {
        return rules.stream().map(CashflowItem::getId).toList();
    }

    private long cashflow(long owner, String direction, LocalDate date, String recurrence,
            LocalDate endDate, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", owner);
        row.put("title", "test");
        row.put("direction", direction);
        row.put("amount", 10000L);
        row.put("scheduled_date", date);
        row.put("recurrence_type", recurrence);
        row.put("recurrence_end_date", endDate);
        row.put("status", status);
        row.put("created_at", LocalDateTime.now(ZoneOffset.UTC));
        row.put("updated_at", LocalDateTime.now(ZoneOffset.UTC));
        return new SimpleJdbcInsert(jdbc).withTableName("cashflow_item").usingGeneratedKeyColumns("cashflow_item_id")
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row).longValue();
    }
}
