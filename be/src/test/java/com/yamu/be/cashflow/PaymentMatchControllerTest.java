package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 예정 지출·결제 매칭 API 4개를 인증 필터·실제 MySQL까지 묶어 확인한다(S15P31E103-319). */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-match-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentMatchControllerTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CashflowService cashflowService;

    OwnershipFixtures ownership;
    CashflowFixtures cashflows;
    long owner;
    long account;
    long card;
    long otherCard;
    long gym;
    long rule;
    /** 매주 반복 규칙의 둘째 회차. 저장된 회차 행이 없다. */
    LocalDate target;

    @BeforeEach
    void setUp() {
        ownership = new OwnershipFixtures(jdbc);
        cashflows = new CashflowFixtures(jdbc);
        owner = ownership.user();
        account = ownership.account(owner);
        card = ownership.card(owner, ownership.product(), account, "ACTIVE");
        otherCard = ownership.card(owner, ownership.product(), account, "ACTIVE");
        gym = cashflows.merchant("SPORTS");
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(14);
        rule = cashflows.cardRule(owner, card, start, "WEEKLY", 30000L, "SPORTS");
        target = start.plusDays(7);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/cashflow-payment-matches").contentType(MediaType.APPLICATION_JSON)
                        .content(body(rule, target, 1L)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/cashflow-items/{id}/payment-match-candidates", rule)
                        .param("occurrenceDate", target.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 확정하면_201이고_같은_쌍을_다시_보내면_200과_같은_매칭이며_회차는_예정에서_빠진다() throws Exception {
        long payment = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");

        String created = confirm(owner, rule, target, payment)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cashflowItemId").value(rule))
                .andExpect(jsonPath("$.data.occurrenceDate").value(target.toString()))
                .andExpect(jsonPath("$.data.paymentId").value(payment))
                .andExpect(jsonPath("$.data.matchStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.warnings", empty()))
                .andExpect(jsonPath("$.data.forecastTreatment").value("ACTUAL_PAYMENT_ONLY"))
                .andReturn().getResponse().getContentAsString();
        long matchId = ((Number) JsonPath.read(created, "$.data.matchId")).longValue();
        String matchedAt = JsonPath.read(created, "$.data.matchedAt");

        confirm(owner, rule, target, payment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchId").value(matchId))
                .andExpect(jsonPath("$.data.matchedAt").value(matchedAt));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match WHERE user_id = ?",
                Integer.class, owner)).isEqualTo(1);

        mockMvc.perform(get("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .param("cashflowItemId", String.valueOf(rule)).param("occurrenceDate", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.match.matchId").value(matchId))
                .andExpect(jsonPath("$.data.match.paymentId").value(payment))
                .andExpect(jsonPath("$.data.match.merchantName").value("테스트 가맹점"))
                .andExpect(jsonPath("$.data.match.paidAmount").value(30000))
                .andExpect(jsonPath("$.data.match.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.match.matchStatus").value("ACTIVE"));
        assertThat(paid(target)).isTrue();
    }

    @Test
    void 금액_카드_날짜_업종이_다르면_경고를_주지만_연결한다() throws Exception {
        long food = cashflows.merchant("FOOD");
        long payment = cashflows.payment(owner, otherCard, food, 25000L, target.plusDays(8), "YAMU", "APPROVED");

        confirm(owner, rule, target, payment)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warnings",
                        contains("AMOUNT_MISMATCH", "CARD_MISMATCH", "DATE_FAR", "MERCHANT_CATEGORY_MISMATCH")));
    }

    @Test
    void 다른_사용자의_회차_결제_매칭은_404다() throws Exception {
        long stranger = ownership.user();
        long strangerCard = ownership.card(stranger, ownership.product(), ownership.account(stranger), "ACTIVE");
        long strangerRule = cashflows.cardRule(stranger, strangerCard, target, "NONE", 30000L, null);
        long strangerPayment = cashflows.payment(stranger, strangerCard, gym, 30000L, target, "YAMU", "APPROVED");
        long payment = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");

        confirm(owner, rule, target, strangerPayment).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        confirm(owner, strangerRule, target, payment).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .param("cashflowItemId", String.valueOf(strangerRule))
                        .param("occurrenceDate", target.toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cashflow-items/{id}/payment-match-candidates", strangerRule)
                        .with(AuthUsers.asUser(owner)).param("occurrenceDate", target.toString()))
                .andExpect(status().isNotFound());

        long strangerMatch = matchId(confirm(stranger, strangerRule, target, strangerPayment)
                .andExpect(status().isCreated()));
        mockMvc.perform(delete("/api/cashflow-payment-matches/{id}", strangerMatch).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match WHERE "
                + "cashflow_payment_match_id = ?", Integer.class, strangerMatch)).isEqualTo(1);
        mockMvc.perform(delete("/api/cashflow-payment-matches/{id}", strangerMatch + 1000)
                        .with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 회차나_결제가_이미_다른_상대와_연결됐으면_409다() throws Exception {
        long first = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");
        long second = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");
        confirm(owner, rule, target, first).andExpect(status().isCreated());

        confirm(owner, rule, target, second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CASHFLOW_MATCH_CONFLICT"));
        confirm(owner, rule, target.plusDays(7), first).andExpect(status().isConflict());
    }

    @Test
    void 카드를_지정하지_않은_예정_지출이나_승인되지_않은_결제_회차가_아닌_날짜는_400이다() throws Exception {
        long accountRule = cashflows.rule(owner, account, target, "NONE", null);
        long approved = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");
        long canceled = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "CANCELED");
        long seed = cashflows.payment(owner, card, gym, 30000L, target, "SEED", "APPROVED");

        confirm(owner, accountRule, target, approved).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("cashflowItemId"));
        confirm(owner, rule, target, canceled).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("paymentId"));
        confirm(owner, rule, target, seed).andExpect(status().isBadRequest());
        confirm(owner, rule, target.plusDays(3), approved).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("occurrenceDate"));
        mockMvc.perform(post("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"cashflowItemId\": %d, \"occurrenceDate\": \"%s\" }".formatted(rule, target)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/cashflow-items/{id}/payment-match-candidates", accountRule)
                        .with(AuthUsers.asUser(owner)).param("occurrenceDate", target.toString()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match WHERE user_id = ?",
                Integer.class, owner)).isZero();
    }

    @Test
    void ID에_소수가_오면_잘라서_다른_결제와_연결하지_않고_400이다() throws Exception {
        long payment = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");

        mockMvc.perform(post("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"cashflowItemId\": %d, \"occurrenceDate\": \"%s\", \"paymentId\": %d.9 }"
                                .formatted(rule, target, payment)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"cashflowItemId\": %d.5, \"occurrenceDate\": \"%s\", \"paymentId\": %d }"
                                .formatted(rule, target, payment)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match WHERE user_id = ?",
                Integer.class, owner)).isZero();
        // 값이 정수인 소수 표기는 받는다(가계부 등록과 같은 기준).
        mockMvc.perform(post("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"cashflowItemId\": %d.0, \"occurrenceDate\": \"%s\", \"paymentId\": %d.0 }"
                                .formatted(rule, target, payment)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentId").value(payment));
    }

    @Test
    void 해제하면_204이고_연결이_없어지며_다시_해제하면_404다() throws Exception {
        long payment = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");
        long matchId = matchId(confirm(owner, rule, target, payment).andExpect(status().isCreated()));

        mockMvc.perform(delete("/api/cashflow-payment-matches/{id}", matchId).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .param("cashflowItemId", String.valueOf(rule)).param("occurrenceDate", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.match").value(nullValue()));
        assertThat(paid(target)).isFalse();
        mockMvc.perform(delete("/api/cashflow-payment-matches/{id}", matchId).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 연결된_결제가_취소되면_REVIEW_REQUIRED이고_회차를_다시_예정으로_센다() throws Exception {
        long payment = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");
        confirm(owner, rule, target, payment).andExpect(status().isCreated());

        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = approved_at "
                + "WHERE payment_transaction_id = ?", payment);

        mockMvc.perform(get("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .param("cashflowItemId", String.valueOf(rule)).param("occurrenceDate", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.match.paymentStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.match.matchStatus").value("REVIEW_REQUIRED"));
        assertThat(paid(target)).isFalse();
        // 같은 쌍을 다시 보내면 기존 연결을 그대로 돌려준다.
        confirm(owner, rule, target, payment).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchStatus").value("REVIEW_REQUIRED"));
    }

    @Test
    void 후보는_점수와_가까운_날_순이고_연결된_결제_기간_밖_시드_취소_결제는_뺀다() throws Exception {
        long food = cashflows.merchant("FOOD");
        long best = cashflows.payment(owner, card, gym, 30000L, target.plusDays(2), "YAMU", "APPROVED");
        long sameAmountNear = cashflows.payment(owner, otherCard, food, 30000L, target.minusDays(1), "YAMU",
                "APPROVED");
        long sameAmountFar = cashflows.payment(owner, otherCard, food, 30000L, target.plusDays(6), "YAMU",
                "APPROVED");
        long cardOnly = cashflows.payment(owner, card, food, 12000L, target, "YAMU", "APPROVED");
        cashflows.payment(owner, card, gym, 30000L, target.plusDays(8), "YAMU", "APPROVED");     // 기간 밖
        cashflows.payment(owner, card, gym, 30000L, target, "SEED", "APPROVED");
        cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "CANCELED");
        long matched = cashflows.payment(owner, card, gym, 30000L, target, "YAMU", "APPROVED");
        confirm(owner, rule, target.minusDays(7), matched).andExpect(status().isCreated());

        mockMvc.perform(get("/api/cashflow-items/{id}/payment-match-candidates", rule)
                        .with(AuthUsers.asUser(owner)).param("occurrenceDate", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduledDate").value(target.toString()))
                .andExpect(jsonPath("$.data.scheduledAmount").value(30000))
                .andExpect(jsonPath("$.data.candidates", hasSize(4)))
                .andExpect(jsonPath("$.data.candidates[*].paymentId")
                        .value(contains((int) best, (int) sameAmountNear, (int) sameAmountFar, (int) cardOnly)))
                .andExpect(jsonPath("$.data.candidates[0].matchReasons",
                        contains("AMOUNT_MATCH", "CARD_MATCH", "MERCHANT_CATEGORY_MATCH", "DATE_NEAR")))
                .andExpect(jsonPath("$.data.candidates[0].warnings", empty()))
                .andExpect(jsonPath("$.data.candidates[0].cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.candidates[2].matchReasons", contains("AMOUNT_MATCH")))
                .andExpect(jsonPath("$.data.candidates[2].warnings",
                        contains("CARD_MISMATCH", "MERCHANT_CATEGORY_MISMATCH")));
    }

    private ResultActions confirm(long user, long item, LocalDate occurrenceDate, long payment) throws Exception {
        return mockMvc.perform(post("/api/cashflow-payment-matches").with(AuthUsers.asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body(item, occurrenceDate, payment)));
    }

    private static String body(long item, LocalDate occurrenceDate, long payment) {
        return """
                { "cashflowItemId": %d, "occurrenceDate": "%s", "paymentId": %d }
                """.formatted(item, occurrenceDate, payment);
    }

    private static long matchId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.matchId"))
                .longValue();
    }

    /** 목록 펼치기가 그 회차를 실제 결제로 보는지(잔고예보가 예정 차감에서 빼는지). */
    private boolean paid(LocalDate occurrenceDate) {
        return cashflowService.list(owner, occurrenceDate, occurrenceDate, null, null, true).stream()
                .filter(entry -> entry.occurrenceDate().equals(occurrenceDate))
                .findFirst().orElseThrow().paid();
    }
}
