package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.account.BankNames;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 잔고예보 설정을 실제 MySQL JSON 칸·가계부 규칙과 함께 확인한다.
 * 동시 수정은 두 트랜잭션이 각자 커밋해야 하므로 테스트 트랜잭션(@Transactional)을 쓰지 않는다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-forecast-settings-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForecastSettingsServiceTest extends MySqlTestSupport {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ForecastSettingsService service;
    @Autowired CashflowService cashflows;

    OwnershipFixtures fixtures;
    long owner;
    long account;
    LocalDate today;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        account = fixtures.account(owner);
        today = LocalDate.now(KOREA);
    }

    @Test
    void 저장한_적이_없으면_명세_기본값이고_기준_계좌를_보여_준다() {
        jdbc.update("UPDATE linked_account SET is_primary = TRUE WHERE linked_account_id = ?", account);

        ForecastSettingsResponse result = service.get(owner);

        assertThat(result).isEqualTo(new ForecastSettingsResponse(account,
                new ForecastSettingsResponse.BaseAccount(account, BankNames.of("088"), "****-****-****-5934"),
                BalanceForecast.Until.NEXT_INCOME, 30, 100_000L, null, null, true, true, null));
    }

    @Test
    void 기준_계좌가_없으면_비워_둔다() {
        ForecastSettingsResponse result = service.get(owner);

        assertThat(result.baseAccountId()).isNull();
        assertThat(result.baseAccount()).isNull();
    }

    @Test
    void 보낸_필드만_바꾸고_null을_보내면_해제한다() {
        service.update(owner, json("""
                { "alertThreshold": 50000, "dailyLivingCostTarget": 20000, "horizon": "DAYS" }"""));

        ForecastSettingsResponse result = service.update(owner, json("""
                { "dailyLivingCostTarget": null, "defaultDays": 14 }"""));

        assertThat(result.alertThreshold()).isEqualTo(50_000L);
        assertThat(result.horizon()).isEqualTo(BalanceForecast.Until.DAYS);
        assertThat(result.dailyLivingCostTarget()).isNull();
        assertThat(result.defaultDays()).isEqualTo(14);
        assertThat(result.includeCandidates()).isTrue();
        assertThat(result.updatedAt()).isNotNull();
        assertThat(service.get(owner)).isEqualTo(result);
        // 기준 계좌는 JSON에 저장하지 않는다(is_primary 하나만 원본).
        assertThat(jdbc.queryForObject("SELECT JSON_CONTAINS_PATH(forecast_settings, 'one', '$.baseAccountId') "
                + "FROM app_user WHERE user_id = ?", Integer.class, owner)).isZero();
    }

    @Test
    void 월급_항목을_이후_전부_고치면_이어진_최신_규칙을_보여_준다() {
        LocalDate first = today.plusDays(1);
        long salary = monthlyIncome(first);
        service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(salary)));

        long raised = cashflows.update(owner, salary, new CashflowService.UpdateCommand(null, "월급(인상)", 3_000_000L,
                null, null, null, null, null, null, null, CashflowService.UpdateScope.THIS_AND_FUTURE,
                first.plusMonths(1))).item().id();

        assertThat(raised).isNotEqualTo(salary);
        assertThat(service.get(owner).income()).isEqualTo(new ForecastSettingsResponse.Income(raised, "월급(인상)",
                3_000_000L, first.getDayOfMonth(), account));
    }

    @Test
    void 뒤_규칙을_지정해도_첫_규칙으로_저장해서_앞_회차를_다시_고쳐도_월급이_끊기지_않는다() {
        LocalDate first = today.plusDays(1);
        long salary = monthlyIncome(first);
        long second = cashflows.update(owner, salary, new CashflowService.UpdateCommand(null, null, 3_000_000L, null,
                null, null, null, null, null, null, CashflowService.UpdateScope.THIS_AND_FUTURE,
                first.plusMonths(2))).item().id();
        service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(second)));
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(forecast_settings, '$.incomeCashflowItemId') FROM app_user "
                + "WHERE user_id = ?", Long.class, owner)).isEqualTo(salary);

        // 앞 회차부터 다시 고치면 뒤 규칙(second)은 지워지고 새 규칙이 생긴다.
        long third = cashflows.update(owner, salary, new CashflowService.UpdateCommand(null, "월급(재수정)", 3_200_000L,
                null, null, null, null, null, null, null, CashflowService.UpdateScope.THIS_AND_FUTURE,
                first.plusMonths(1))).item().id();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE cashflow_item_id = ?", Integer.class,
                second)).isZero();
        assertThat(service.get(owner).income()).isEqualTo(new ForecastSettingsResponse.Income(third, "월급(재수정)",
                3_200_000L, first.getDayOfMonth(), account));
    }

    @Test
    void 종료일이_오늘이어도_이번_달_입금일이_지났으면_남은_월급이_없다() {
        // 어제가 입금일이고 오늘 끝나는 규칙: 종료일(오늘)은 지나지 않았지만 앞으로 올 회차가 없다.
        LocalDate yesterday = today.minusDays(1);
        long salary = startedMonthlyIncome(yesterday.minusMonths(2), yesterday.getDayOfMonth(), today);

        assertInvalid(() -> service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(salary))),
                "incomeCashflowItemId");

        // 저장돼 있던 값이라도 조회하면 월급 없음이다.
        jdbc.update("UPDATE app_user SET forecast_settings = JSON_OBJECT('incomeCashflowItemId', ?) WHERE user_id = ?",
                salary, owner);
        assertThat(service.get(owner).income()).isNull();
    }

    @Test
    void 시작하지_않은_월급_규칙을_지우면_월급이_없다() {
        long salary = monthlyIncome(today.plusDays(1));
        service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(salary)));

        cashflows.delete(owner, salary, CashflowService.DeleteScope.ALL, null);

        assertThat(service.get(owner).income()).isNull();
    }

    @Test
    void 시작한_월급_규칙을_전체_삭제하면_어제로_닫혀_월급이_없고_다시_지정할_수도_없다() {
        long salary = startedMonthlyIncome(today.minusMonths(2), null, null);
        service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(salary)));
        assertThat(service.get(owner).income()).isNotNull();

        cashflows.delete(owner, salary, CashflowService.DeleteScope.ALL, null);

        // 행은 남아 있고 종료일만 어제다.
        assertThat(jdbc.queryForObject("SELECT recurrence_end_date FROM cashflow_item WHERE cashflow_item_id = ?",
                LocalDate.class, salary)).isEqualTo(today.minusDays(1));
        assertThat(service.get(owner).income()).isNull();
        assertInvalid(() -> service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(salary))),
                "incomeCashflowItemId");
    }

    @Test
    void 월급으로_쓸_수_없는_항목은_400이고_없거나_남의_항목은_404다() {
        long expense = cashflows.create(owner, new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE,
                500_000L, today.plusDays(1), CashflowItem.RecurrenceType.MONTHLY, null, null, null, account, null,
                null, null)).id();
        long once = cashflows.create(owner, new CashflowService.CreateCommand("보너스", CashflowItem.Direction.INCOME,
                500_000L, today.plusDays(1), CashflowItem.RecurrenceType.NONE, null, null, null, account, null,
                null, null)).id();
        long stranger = fixtures.user();
        long strangersSalary = cashflows.create(stranger, new CashflowService.CreateCommand("월급",
                CashflowItem.Direction.INCOME, 2_800_000L, today.plusDays(1), CashflowItem.RecurrenceType.MONTHLY,
                null, null, null, fixtures.account(stranger), null, null, null)).id();

        assertInvalid(() -> service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(expense))),
                "incomeCashflowItemId");
        assertInvalid(() -> service.update(owner, json("{ \"incomeCashflowItemId\": %d }".formatted(once))),
                "incomeCashflowItemId");
        assertThatThrownBy(() -> service.update(owner,
                json("{ \"incomeCashflowItemId\": %d }".formatted(strangersSalary))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.update(owner, json("{ \"incomeCashflowItemId\": 999999999 }")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        // 거절된 요청은 아무것도 저장하지 않는다.
        assertThat(service.get(owner).updatedAt()).isNull();
    }

    @Test
    void 다른_필드를_동시에_고쳐도_두_변경이_모두_남는다() throws Exception {
        List<Callable<ForecastSettingsResponse>> tasks = List.of(
                () -> service.update(owner, json("{ \"alertThreshold\": 70000 }")),
                () -> service.update(owner, json("{ \"defaultDays\": 7 }")));

        Concurrently.run(tasks);

        ForecastSettingsResponse result = service.get(owner);
        assertThat(result.alertThreshold()).isEqualTo(70_000L);
        assertThat(result.defaultDays()).isEqualTo(7);
    }

    @Test
    void 저장값이_범위_밖이면_기본값으로_덮지_않고_실패한다() {
        jdbc.update("UPDATE app_user SET forecast_settings = '{\"defaultDays\": 10}' WHERE user_id = ?", owner);

        assertThatThrownBy(() -> service.get(owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userId=" + owner)
                .hasMessageNotContaining("10}");
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException error = (BusinessException) e;
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(error.getDetails().toString()).contains(field);
                });
    }

    @Test
    void 다음_월급일은_기준일보다_뒤의_첫_회차이고_수정_전_규칙의_회차도_센다() {
        LocalDate first = today.plusDays(3);
        long salary = monthlyIncome(first);
        // 둘째 회차부터 바꾸면 첫 회차는 수정 전 규칙(salary)에 남는다
        cashflows.update(owner, salary, new CashflowService.UpdateCommand(null, "월급(인상)", 3_000_000L, null, null,
                null, null, null, null, null, CashflowService.UpdateScope.THIS_AND_FUTURE, first.plusMonths(1)));

        assertThat(service.nextIncomeDate(owner, salary, today)).contains(first);
        // 기준일 당일 회차는 빼고 그 뒤의 첫 회차(바뀐 규칙)다. 말일 보정이 있어 달만 본다
        assertThat(service.nextIncomeDate(owner, salary, first))
                .hasValueSatisfying(date -> assertThat(YearMonth.from(date))
                        .isEqualTo(YearMonth.from(first).plusMonths(1)));
    }

    @Test
    void 월급을_정하지_않았거나_남의_항목이면_다음_월급일이_없다() {
        long stranger = fixtures.user();
        long strangerSalary = cashflows.create(stranger, new CashflowService.CreateCommand("월급",
                CashflowItem.Direction.INCOME, 2_800_000L, today.plusDays(3), CashflowItem.RecurrenceType.MONTHLY,
                null, null, null, fixtures.account(stranger), null, null, null)).id();

        assertThat(service.nextIncomeDate(owner, null, today)).isEmpty();
        assertThat(service.nextIncomeDate(owner, strangerSalary, today)).isEmpty();
    }

    private long monthlyIncome(LocalDate first) {
        return cashflows.create(owner, new CashflowService.CreateCommand("월급", CashflowItem.Direction.INCOME,
                2_800_000L, first, CashflowItem.RecurrenceType.MONTHLY, null, null, null, account, null, null,
                null)).id();
    }

    /** 이미 시작한 매월 수입. 등록 API는 과거 날짜를 받지 않아 행을 직접 넣는다. 기준일·종료일은 null이면 넣지 않는다. */
    private long startedMonthlyIncome(LocalDate first, Integer dayOfMonth, LocalDate end) {
        Timestamp now = Timestamp.from(Instant.now());
        Map<String, Object> row = new HashMap<>(Map.of("user_id", owner, "linked_account_id", account,
                "title", "월급", "direction", "INCOME", "amount", 2_800_000L, "scheduled_date", first,
                "recurrence_type", "MONTHLY", "status", "CONFIRMED", "created_at", now, "updated_at", now));
        if (dayOfMonth != null) {
            row.put("recurrence_day_of_month", dayOfMonth);
        }
        if (end != null) {
            row.put("recurrence_end_date", end);
        }
        return new SimpleJdbcInsert(jdbc).withTableName("cashflow_item").usingGeneratedKeyColumns("cashflow_item_id")
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row).longValue();
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
