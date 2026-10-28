package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 가계부 등록·목록 API를 인증 필터·BE 1 소유 확인·실제 MySQL까지 묶어 확인한다. */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-cashflow-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CashflowControllerTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    OwnershipFixtures fixtures;
    long owner;
    long account;
    long product;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        account = fixtures.account(owner);
        product = fixtures.product();
        tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").contentType(MediaType.APPLICATION_JSON).content(accountExpense(account)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/cashflow-items"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 등록하면_201과_명세_이름의_응답을_돌려주고_로그인한_사용자로_저장한다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "월세", "amount": 500000, "date": "%s",
                                  "recurrence": { "frequency": "MONTHLY", "dayOfMonth": %d },
                                  "merchantCategoryCode": "H01-RENT", "accountId": %d }
                                """.formatted(tomorrow, tomorrow.getDayOfMonth(), account)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.occurrenceDate").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.recurrence.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.data.recurrence.dayOfMonth").value(tomorrow.getDayOfMonth()))
                .andExpect(jsonPath("$.data.merchantCategoryCode").value("H01-RENT"))
                .andExpect(jsonPath("$.data.accountId").value(account))
                .andExpect(jsonPath("$.data.productName").value(nullValue()))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ? AND title = '월세'",
                Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void 형식이_틀린_본문은_필드별_사유와_400이다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": " ", "amount": 0, "date": "%s",
                                  "recurrence": { "dayOfMonth": 32 }, "accountId": %d }
                                """.formatted(tomorrow, account)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[*].field", hasItem("title")))
                .andExpect(jsonPath("$.error.details.fieldErrors[*].field", hasItem("amount")))
                .andExpect(jsonPath("$.error.details.fieldErrors[*].field", hasItem("recurrence.frequency")))
                .andExpect(jsonPath("$.error.details.fieldErrors[*].field", hasItem("recurrence.dayOfMonth")));
    }

    @Test
    void 남의_계좌로는_404이고_저장하지_않는다() throws Exception {
        long strangerAccount = fixtures.account(fixtures.user());

        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(accountExpense(strangerAccount)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ?", Integer.class, owner))
                .isZero();
    }

    @Test
    void 카드사_정지_카드는_400이다() throws Exception {
        long card = fixtures.card(owner, product, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET issuer_status = 'SUSPENDED' WHERE user_card_id = ?", card);

        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "구독", "amount": 10000, "date": "%s", "userCardId": %d }
                                """.formatted(tomorrow, card)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("userCardId"));
    }

    @Test
    void 목록은_로그인한_사용자의_항목만_돌려준다() throws Exception {
        long stranger = fixtures.user();
        long strangerAccount = fixtures.account(stranger);
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                .contentType(MediaType.APPLICATION_JSON).content(accountExpense(account)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(stranger))
                .contentType(MediaType.APPLICATION_JSON).content(accountExpense(strangerAccount)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].accountId").value(account))
                .andExpect(jsonPath("$.data.candidateCount").value(0));
    }

    @Test
    void 목록은_기본으로_반복_항목을_발생일마다_펼치고_expand_false면_한_건이다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "헬스장", "amount": 89000, "date": "%s",
                                  "recurrence": { "frequency": "WEEKLY" }, "accountId": %d }
                                """.formatted(tomorrow, account)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.plusDays(14).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].occurrenceDate").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.items[1].occurrenceDate").value(tomorrow.plusDays(7).toString()))
                .andExpect(jsonPath("$.data.items[2].occurrenceDate").value(tomorrow.plusDays(14).toString()))
                .andExpect(jsonPath("$.data.items[2].recurrence.frequency").value("WEEKLY"));
        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.plusDays(14).toString())
                        .param("expand", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].occurrenceDate").value(tomorrow.toString()));
    }

    @Test
    void 목록은_옮긴_회차를_실제_예정일로_보여_주고_결제_연결_회차는_저장된_값을_쓴다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "헬스장", "amount": 89000, "date": "%s",
                                  "recurrence": { "frequency": "WEEKLY" }, "accountId": %d }
                                """.formatted(tomorrow, account)))
                .andExpect(status().isCreated());
        long rule = jdbc.queryForObject("SELECT cashflow_item_id FROM cashflow_item WHERE user_id = ?", Long.class, owner);
        long first = jdbc.queryForObject("SELECT cashflow_occurrence_id FROM cashflow_occurrence WHERE cashflow_item_id = ?",
                Long.class, rule);
        // 둘째 회차만 이틀 뒤로 옮기고 금액을 바꾼다.
        insertRow("cashflow_occurrence", "cashflow_occurrence_id", Map.of("cashflow_item_id", rule, "user_id", owner,
                "occurrence_date", tomorrow.plusDays(7), "scheduled_date", tomorrow.plusDays(9), "title", "헬스장",
                "direction", "EXPENSE", "amount", 50000L, "linked_account_id", account, "status", "CONFIRMED",
                "is_overridden", true));
        // 첫 회차는 결제와 연결한 뒤 규칙 금액이 바뀐다.
        paymentMatch(owner, first);
        jdbc.update("UPDATE cashflow_item SET amount = 99000 WHERE cashflow_item_id = ?", rule);
        // 등록 요청이 같은 테스트 트랜잭션에 올려 둔 엔티티를 버려, SQL로 바꾼 값을 다시 읽게 한다.
        em.clear();

        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.plusDays(14).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.items[0].amount").value(89000))
                .andExpect(jsonPath("$.data.items[1].occurrenceDate").value(tomorrow.plusDays(7).toString()))
                .andExpect(jsonPath("$.data.items[1].date").value(tomorrow.plusDays(9).toString()))
                .andExpect(jsonPath("$.data.items[1].amount").value(50000))
                .andExpect(jsonPath("$.data.items[2].date").value(tomorrow.plusDays(14).toString()))
                .andExpect(jsonPath("$.data.items[2].amount").value(99000));
        // 원래 회차만 기간 안이면 보이지 않는다.
        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.plusDays(6).toString()).param("to", tomorrow.plusDays(8).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 목록의_잘못된_조회_값은_400이다() throws Exception {
        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner)).param("status", "DELETED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.plusDays(366).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("to"));
    }

    @Test
    void DB가_담을_수_없는_날짜는_500이_아니라_400이다() throws Exception {
        for (String from : new String[] {"9999-12-31", "+999999999-12-31"}) {
            mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner)).param("from", from))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("from"));
        }
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "월세", "amount": 500000, "date": "+10000-01-01", "accountId": %d }
                                """.formatted(account)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("date"));
    }

    /** 결제 기능이 아직 연결 행을 만들지 않아, 실제 결제와 연결 행을 SQL로 넣는다. */
    private void paymentMatch(long user, long occurrence) {
        long merchant = insertRow("merchant", "merchant_id", Map.of("merchant_name", "테스트 헬스장",
                "brand_code", UUID.randomUUID().toString(), "merchant_category_code", "SPORTS"));
        long payment = insertRow("payment_transaction", "payment_transaction_id", Map.of("user_id", user,
                "merchant_id", merchant, "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", new byte[32], "source", "YAMU", "status", "REQUESTED",
                "requested_amount", 89000L, "requested_at", LocalDateTime.now(ZoneOffset.UTC)));
        Map<String, Object> match = new HashMap<>(Map.of("user_id", user, "cashflow_occurrence_id", occurrence,
                "payment_transaction_id", payment, "matched_at", LocalDateTime.now(ZoneOffset.UTC),
                "created_at", LocalDateTime.now(ZoneOffset.UTC)));
        new SimpleJdbcInsert(jdbc).withTableName("cashflow_payment_match").usingColumns(match.keySet().toArray(String[]::new))
                .execute(match);
    }

    private long insertRow(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.put("created_at", LocalDateTime.now(ZoneOffset.UTC));
        row.put("updated_at", LocalDateTime.now(ZoneOffset.UTC));
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row).longValue();
    }

    private String accountExpense(long accountId) {
        return """
                { "type": "EXPENSE", "title": "월세", "amount": 500000, "date": "%s", "accountId": %d }
                """.formatted(tomorrow, accountId);
    }
}
