package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
    void 금액이_1000억_원을_넘으면_등록과_수정_모두_400이다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "월세", "amount": 100000000001, "date": "%s", "accountId": %d }
                                """.formatted(tomorrow, account)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("amount"));
        long itemId = new CashflowFixtures(jdbc).rule(owner, account, tomorrow, "NONE", null);
        mockMvc.perform(patch("/api/cashflow-items/{id}", itemId).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"amount\": 100000000001 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("amount"));
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
        // 둘째 회차만 이틀 뒤로 옮기고 금액을 5000으로 바꾼다.
        CashflowFixtures cashflows = new CashflowFixtures(jdbc);
        cashflows.occurrence(owner, account, rule, tomorrow.plusDays(7), tomorrow.plusDays(9), true);
        // 첫 회차는 결제와 연결한 뒤 규칙 금액이 바뀐다.
        cashflows.paymentMatch(owner, first);
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
                .andExpect(jsonPath("$.data.items[1].amount").value(5000))
                .andExpect(jsonPath("$.data.items[2].date").value(tomorrow.plusDays(14).toString()))
                .andExpect(jsonPath("$.data.items[2].amount").value(99000));
        // 원래 회차만 기간 안이면 보이지 않는다.
        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.plusDays(6).toString()).param("to", tomorrow.plusDays(8).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        // 결제와 연결된 회차는 고칠 수 없다.
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "scope": "THIS_ONLY", "occurrenceDate": "%s", "amount": 1000 }
                                """.formatted(tomorrow)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAID_OCCURRENCE_LOCKED"));
    }

    @Test
    void 이번_회차만_고치고_지우면_목록에_그_회차만_반영된다() throws Exception {
        long rule = weekly();
        LocalDate second = tomorrow.plusDays(7);

        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "scope": "THIS_ONLY", "occurrenceDate": "%s", "date": "%s" }
                                """.formatted(second, tomorrow.plusDays(9))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occurrenceDate").value(second.toString()))
                .andExpect(jsonPath("$.data.date").value(tomorrow.plusDays(9).toString()));
        // 옮긴 회차에 금액만 보내도 옮긴 날짜는 그대로다.
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "scope": "THIS_ONLY", "occurrenceDate": "%s", "amount": 50000 }
                                """.formatted(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(tomorrow.plusDays(9).toString()))
                .andExpect(jsonPath("$.data.amount").value(50000));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                            .param("scope", "THIS_ONLY").param("occurrenceDate", tomorrow.plusDays(14).toString()))
                    .andExpect(status().isNoContent());
        }
        em.clear();

        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.plusDays(21).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].date").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.items[0].amount").value(89000))
                .andExpect(jsonPath("$.data.items[1].occurrenceDate").value(second.toString()))
                .andExpect(jsonPath("$.data.items[1].date").value(tomorrow.plusDays(9).toString()))
                .andExpect(jsonPath("$.data.items[1].amount").value(50000))
                .andExpect(jsonPath("$.data.items[2].date").value(tomorrow.plusDays(21).toString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE cashflow_item_id = ?",
                Integer.class, rule)).isEqualTo(3);
    }

    @Test
    void scope를_생략하면_이후_전부_수정과_전체_삭제다() throws Exception {
        long rule = weekly();

        // 아직 시작하지 않은 규칙이라 제자리에서 바뀌고 ID가 그대로다.
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"amount\": 20000 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashflowItemId").value(rule))
                .andExpect(jsonPath("$.data.amount").value(20000))
                .andExpect(jsonPath("$.data.recurrence.frequency").value("WEEKLY"));
        mockMvc.perform(delete("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .param("from", tomorrow.toString()).param("to", tomorrow.plusDays(14).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE cashflow_item_id = ?",
                Integer.class, rule)).isZero();
    }

    @Test
    void 탐지_후보는_확인할_수_있고_확정된_항목을_무시로_바꾸면_409다() throws Exception {
        long rule = weekly();
        jdbc.update("UPDATE cashflow_item SET status = 'CANDIDATE' WHERE cashflow_item_id = ?", rule);
        em.clear();

        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"CONFIRMED\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"IGNORED\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_CHANGE"));
    }

    @Test
    void 탐지_후보를_지우면_204이고_무시로_남는다() throws Exception {
        long rule = weekly();
        jdbc.update("UPDATE cashflow_item SET status = 'CANDIDATE' WHERE cashflow_item_id = ?", rule);
        em.clear();

        mockMvc.perform(delete("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNoContent());
        em.flush();

        assertThat(jdbc.queryForObject("SELECT status FROM cashflow_item WHERE cashflow_item_id = ?",
                String.class, rule)).isEqualTo("IGNORED");
    }

    @Test
    void 남의_항목_수정_삭제는_404이고_토큰이_없으면_401이다() throws Exception {
        long rule = weekly();
        long stranger = fixtures.user();
        String body = """
                { "scope": "THIS_ONLY", "occurrenceDate": "%s", "amount": 1000 }
                """.formatted(tomorrow);

        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(stranger))
                        .param("scope", "THIS_ONLY").param("occurrenceDate", tomorrow.toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/cashflow-items/{id}", rule).param("scope", "WHATEVER").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE cashflow_item_id = ?",
                Integer.class, rule)).isEqualTo(1);
    }

    @Test
    void 회차의_계좌나_카드를_바꿀_때도_소유와_카드_상태를_확인한다() throws Exception {
        long rule = weekly();
        long strangerAccount = fixtures.account(fixtures.user());
        long suspended = fixtures.card(owner, product, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET issuer_status = 'SUSPENDED' WHERE user_card_id = ?", suspended);

        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "scope": "THIS_ONLY", "occurrenceDate": "%s", "accountId": %d }
                                """.formatted(tomorrow, strangerAccount)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "scope": "THIS_ONLY", "occurrenceDate": "%s", "userCardId": %d }
                                """.formatted(tomorrow, suspended)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("userCardId"));
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

    @Test
    void 금액_계좌에_소수점_아래가_있으면_잘라_저장하지_않고_400이다() throws Exception {
        // S15P31E103-318: 전에는 500000.9를 500000원으로, 계좌 3.9를 3번 계좌로 저장했다
        for (String field : new String[] {"\"amount\": 500000.9, \"accountId\": %d", "\"amount\": 500000, \"accountId\": %d.9"}) {
            mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{ \"type\": \"EXPENSE\", \"title\": \"월세\", \"date\": \"%s\", " + field + " }")
                                    .formatted(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1), account)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void 등록의_enum_칸에_숫자나_이름이_다른_문자열이_오면_순번으로_읽지_않고_400이다() throws Exception {
        // S15P31E103-339: 전에는 "type": 0을 INCOME으로, 요일 순번을 그 요일로 저장했다
        String recurrence = "\"recurrence\": { \"frequency\": %s, \"dayOfWeek\": %s }";
        String weekly = "\"WEEKLY\"";
        String day = '"' + tomorrow.getDayOfWeek().name() + '"';
        String[] bodies = {
                "\"type\": 0",
                "\"type\": \"0\"",
                "\"type\": \"income\"",
                "\"type\": \" INCOME \"",
                "\"type\": \"EXPENSE\", " + recurrence.formatted(CashflowItem.RecurrenceType.WEEKLY.ordinal(), day),
                "\"type\": \"EXPENSE\", " + recurrence.formatted(weekly, tomorrow.getDayOfWeek().ordinal()),
                "\"type\": \"EXPENSE\", " + recurrence.formatted(weekly, '"' + String.valueOf(tomorrow.getDayOfWeek().ordinal()) + '"'),
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{ " + body + ", \"title\": \"월세\", \"amount\": 500000, \"date\": \"%s\", \"accountId\": %d }")
                                    .formatted(tomorrow, account)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    // 본문 형식 오류로 거절한다(금액 소수 거절과 같은 경로라 칸별 사유는 없다)
                    .andExpect(jsonPath("$.error.details.fieldErrors").doesNotExist());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ?", Integer.class, owner))
                .isZero();
    }

    @Test
    void 수정의_enum_칸에_숫자가_오면_순번으로_읽지_않고_400이며_바꾸지_않는다() throws Exception {
        long rule = new CashflowFixtures(jdbc).rule(owner, account, tomorrow, "NONE", null);
        jdbc.update("UPDATE cashflow_item SET status = 'CANDIDATE' WHERE cashflow_item_id = ?", rule);
        String[] bodies = {
                // 0은 CONFIRMED 순번이다. 전에는 탐지 후보를 확인으로 바꿨다
                "{ \"status\": 0 }",
                "{ \"status\": \"0\" }",
                // 1은 THIS_ONLY 순번이다. 전에는 이번 회차만 고쳤다
                "{ \"scope\": 1, \"occurrenceDate\": \"%s\", \"amount\": 1000 }".formatted(tomorrow),
                "{ \"type\": 1, \"amount\": 1000 }",
                "{ \"recurrence\": { \"frequency\": 0 } }",
        };
        for (String body : bodies) {
            mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.details.fieldErrors").doesNotExist());
        }
        em.flush();
        em.clear();
        assertThat(jdbc.queryForObject("SELECT status FROM cashflow_item WHERE cashflow_item_id = ?", String.class, rule))
                .isEqualTo("CANDIDATE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE cashflow_item_id = ? AND amount <> 10000",
                Integer.class, rule)).isZero();
    }

    @Test
    void enum_칸의_이름_문자열은_그대로_받는다() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "헬스장", "amount": 89000, "date": "%s",
                                  "recurrence": { "frequency": "WEEKLY", "dayOfWeek": "%s" }, "accountId": %d }
                                """.formatted(tomorrow, tomorrow.getDayOfWeek(), account)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recurrence.dayOfWeek").value(tomorrow.getDayOfWeek().name()));

        long rule = new CashflowFixtures(jdbc).rule(owner, account, tomorrow, "NONE", null);
        jdbc.update("UPDATE cashflow_item SET status = 'CANDIDATE' WHERE cashflow_item_id = ?", rule);
        mockMvc.perform(patch("/api/cashflow-items/{id}", rule).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"CONFIRMED\", \"scope\": \"THIS_AND_FUTURE\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    /** 내일부터 매주 반복하는 계좌 지출을 등록하고 규칙 ID를 돌려준다. */
    private long weekly() throws Exception {
        mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "EXPENSE", "title": "헬스장", "amount": 89000, "date": "%s",
                                  "recurrence": { "frequency": "WEEKLY" }, "accountId": %d }
                                """.formatted(tomorrow, account)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT MAX(cashflow_item_id) FROM cashflow_item WHERE user_id = ?", Long.class, owner);
    }

    private String accountExpense(long accountId) {
        return """
                { "type": "EXPENSE", "title": "월세", "amount": 500000, "date": "%s", "accountId": %d }
                """.formatted(tomorrow, accountId);
    }
}
