package com.yamu.be.report;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월간 소비·혜택 리포트를 인증 필터·실제 MySQL까지 묶어 확인한다(S15P31E103-372). 결제·혜택 행은 승인 API 없이 SQL로 바로 넣는다.
 * 소비 분류는 직접 넣는다(요청 시 분류기는 이미 분류된 결제를 건드리지 않는다).
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-report-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportIntegrationTest extends MySqlTestSupport {

    static final String OCTOBER = "2026-10";
    static final LocalDateTime OCT_10 = LocalDateTime.of(2026, 10, 10, 3, 0);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    long product;
    long card;
    long secondCard;
    long otherCard;
    long cafe;
    long mart;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        product = fixtures.product();
        card = fixtures.card(owner, product, null, "ACTIVE");
        secondCard = fixtures.card(owner, product, null, "ACTIVE");
        otherCard = fixtures.card(other, product, null, "ACTIVE");
        cafe = merchant("스타벅스", "COFFEE_SHOP");
        mart = merchant("이마트", "HYPERMARKET");
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/reports/monthly")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports/monthly/benefits")).andExpect(status().isUnauthorized());
    }

    @Test
    void 결제가_없는_달은_0원과_빈_목록이다() throws Exception {
        mockMvc.perform(monthly(OCTOBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value(OCTOBER))
                .andExpect(jsonPath("$.data.isPartialMonth").value(false))
                .andExpect(jsonPath("$.data.periodTo").value("2026-10-31"))
                .andExpect(jsonPath("$.data.totalSpent").value(0))
                .andExpect(jsonPath("$.data.livingSpent").value(0))
                .andExpect(jsonPath("$.data.prevSamePeriodSpent").value(0))
                .andExpect(jsonPath("$.data.byCategory", hasSize(0)))
                .andExpect(jsonPath("$.data.benefitSummary.expected").value(0));
        mockMvc.perform(benefits(OCTOBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.summary.expectedBenefit").value(0))
                .andExpect(jsonPath("$.data.summary.missedBenefit").value(0))
                .andExpect(jsonPath("$.data.differences", hasSize(0)))
                .andExpect(jsonPath("$.data.byCategory", hasSize(0)));
    }

    @Test
    void 승인_결제만_업종별로_더하고_비율은_소수_첫째_자리다() throws Exception {
        approved(owner, card, cafe, OCT_10, 10_000);
        approved(owner, card, cafe, OCT_10.plusHours(1), 20_000);
        approved(owner, card, mart, OCT_10.plusHours(2), 60_000);
        long canceled = approved(owner, card, mart, OCT_10.plusHours(3), 500_000);
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(4), canceled);
        payment(owner, card, mart, "DECLINED", OCT_10.plusHours(5), 70_000, "YAMU");

        mockMvc.perform(monthly(OCTOBER))
                .andExpect(jsonPath("$.data.totalSpent").value(90_000))
                .andExpect(jsonPath("$.data.byCategory[*].categoryCode").value(contains("HYPERMARKET", "COFFEE_SHOP")))
                .andExpect(jsonPath("$.data.byCategory[0].categoryName").value("대형마트"))
                .andExpect(jsonPath("$.data.byCategory[0].amount").value(60_000))
                .andExpect(jsonPath("$.data.byCategory[0].ratio").value(66.7))
                .andExpect(jsonPath("$.data.byCategory[1].categoryName").value("카페"))
                .andExpect(jsonPath("$.data.byCategory[1].ratio").value(33.3));
    }

    @Test
    void 목록에_없는_업종_코드는_코드를_이름으로_쓴다() throws Exception {
        long unknown = merchant("어딘가", "F01-UNKNOWN");
        approved(owner, card, unknown, OCT_10, 1_000);

        mockMvc.perform(monthly(OCTOBER))
                .andExpect(jsonPath("$.data.byCategory[0].categoryCode").value("F01-UNKNOWN"))
                .andExpect(jsonPath("$.data.byCategory[0].categoryName").value("F01-UNKNOWN"));
    }

    @Test
    void 생활_소비와_지난달_같은_기간_생활_소비를_더한다() throws Exception {
        living(approved(owner, card, cafe, OCT_10, 10_000));
        approved(owner, card, mart, OCT_10.plusHours(1), 1_500_000);   // 분류 LARGE
        jdbc.update("UPDATE payment_transaction SET spending_class = 'LARGE', spending_class_source = 'USER' "
                + "WHERE requested_amount = 1500000");
        // 9월: 30일 하루 전체가 같은 기간에 들어간다(10월 보고서는 말일 31일까지라 9월은 말일 30일까지)
        living(approved(owner, card, cafe, LocalDateTime.of(2026, 9, 30, 14, 0), 3_000));
        living(approved(owner, card, cafe, LocalDateTime.of(2026, 9, 1, 1, 0), 2_000));

        mockMvc.perform(monthly(OCTOBER))
                .andExpect(jsonPath("$.data.totalSpent").value(1_510_000))
                .andExpect(jsonPath("$.data.livingSpent").value(10_000))
                .andExpect(jsonPath("$.data.prevSamePeriodSpent").value(5_000))
                .andExpect(jsonPath("$.data.prevMonthTotalSpent").value(5_000));
    }

    @Test
    void 지난달_같은_기간은_지난달_말일에서_자른다() throws Exception {
        // 3월 보고서(말일 31일)의 지난달은 2월 28일까지다
        living(approved(owner, card, cafe, LocalDateTime.of(2026, 2, 28, 5, 0), 4_000));
        living(approved(owner, card, cafe, LocalDateTime.of(2026, 3, 31, 5, 0), 1_000));

        mockMvc.perform(monthly("2026-03"))
                .andExpect(jsonPath("$.data.livingSpent").value(1_000))
                .andExpect(jsonPath("$.data.prevSamePeriodSpent").value(4_000));
    }

    @Test
    void 월은_한국_날짜로_자른다() throws Exception {
        // 11월 1일 00:30(한국)은 UTC로 10월 31일 15:30이다
        approved(owner, card, cafe, LocalDateTime.of(2026, 10, 31, 15, 30), 1_000);
        approved(owner, card, cafe, LocalDateTime.of(2026, 10, 31, 14, 59), 2_000);

        mockMvc.perform(monthly("2026-11")).andExpect(jsonPath("$.data.totalSpent").value(1_000));
        mockMvc.perform(monthly(OCTOBER)).andExpect(jsonPath("$.data.totalSpent").value(2_000));
    }

    @Test
    void 남의_결제는_리포트에_섞이지_않는다() throws Exception {
        long others = approved(other, otherCard, cafe, OCT_10, 50_000);
        benefit(others, 5_000, "AT_APPROVAL");
        recommend(others, otherCard, 9_000, null, null);

        mockMvc.perform(monthly(OCTOBER))
                .andExpect(jsonPath("$.data.totalSpent").value(0))
                .andExpect(jsonPath("$.data.benefitSummary.expected").value(0));
        mockMvc.perform(benefits(OCTOBER))
                .andExpect(jsonPath("$.data.summary.expectedBenefit").value(0))
                .andExpect(jsonPath("$.data.differences", hasSize(0)));
    }

    @Test
    void 잘못된_yearMonth는_400이다() throws Exception {
        mockMvc.perform(monthly("2026-13")).andExpect(status().isBadRequest());
        mockMvc.perform(benefits("202610")).andExpect(status().isBadRequest());
    }

    @Test
    void 혜택은_승인_결제만_더하고_확정_전이_있으면_PARTIAL이다() throws Exception {
        long instant = approved(owner, card, cafe, OCT_10, 10_000);
        benefit(instant, 1_000, "AT_APPROVAL");
        long billing = approved(owner, card, mart, OCT_10.plusHours(1), 20_000);
        benefit(billing, 2_000, "AT_BILLING");
        long canceled = approved(owner, card, mart, OCT_10.plusHours(2), 30_000);
        benefit(canceled, 3_000, "AT_APPROVAL");
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(3), canceled);

        mockMvc.perform(benefits(OCTOBER))
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.summary.expectedBenefit").value(3_000))
                .andExpect(jsonPath("$.data.summary.confirmedBenefit").value(1_000))
                .andExpect(jsonPath("$.data.byCategory[*].categoryCode").value(contains("HYPERMARKET", "COFFEE_SHOP")))
                .andExpect(jsonPath("$.data.byCategory[0].amount").value(2_000));
        mockMvc.perform(monthly(OCTOBER))
                .andExpect(jsonPath("$.data.benefitSummary.expected").value(3_000))
                .andExpect(jsonPath("$.data.benefitSummary.actual").value(1_000));

        // 청구할인 회차가 청구 확정되면 지난 달은 CONFIRMED
        insert("bill_item", "bill_item_id", timestamps(Map.of("payment_transaction_id", billing, "user_id", owner,
                "user_card_id", card, "installment_no", 1, "billing_month", LocalDate.of(2026, 11, 1),
                "principal_amount", 20_000, "fee_amount", 0, "billed_amount", 20_000, "status", "BILLED")));
        mockMvc.perform(benefits(OCTOBER))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.summary.confirmedBenefit").value(3_000));
    }

    @Test
    void 이번_달은_모두_확정이어도_PARTIAL이다() throws Exception {
        mockMvc.perform(get("/api/reports/monthly/benefits").param("yearMonth",
                        java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul")).toString()).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.status").value("PARTIAL"));
        mockMvc.perform(get("/api/reports/monthly").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.isPartialMonth").value(true));
    }

    @Test
    void 놓친_혜택은_추천_1순위보다_적게_받은_야무_결제다() throws Exception {
        // 1순위가 아닌 카드로 승인: 1순위 3,000.9원(내림 3,000) vs 실제 1,000원
        long userSelected = approved(owner, secondCard, cafe, OCT_10, 30_000);
        benefit(userSelected, 1_000, "AT_APPROVAL");
        recommend(userSelected, card, "3000.9", secondCard, "1000");
        // 1순위 카드였지만 승인 때 한도가 줄어 500원만 받음
        long limitPartial = approved(owner, card, mart, OCT_10.plusHours(1), 20_000);
        benefit(limitPartial, 500, "AT_APPROVAL");
        recommend(limitPartial, card, "2000", null, null);
        // 1순위로 승인하고 그대로 받음 → 놓친 혜택 아님
        long asRecommended = approved(owner, card, cafe, OCT_10.plusHours(2), 10_000);
        benefit(asRecommended, 1_000, "AT_APPROVAL");
        recommend(asRecommended, card, "1000", null, null);
        // 취소 건 · 추천 결과 없는 건 · SEED 건 · 원화 미환산 혜택이 있는 건은 판정하지 않는다
        long canceled = approved(owner, secondCard, cafe, OCT_10.plusHours(3), 10_000);
        recommend(canceled, card, "5000", null, null);
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(4), canceled);
        approved(owner, secondCard, cafe, OCT_10.plusHours(5), 10_000);
        long seed = payment(owner, secondCard, cafe, "APPROVED", OCT_10.plusHours(6), 10_000, "SEED");
        recommend(seed, card, "5000", null, null);
        long unconverted = approved(owner, card, cafe, OCT_10.plusHours(7), 10_000);
        long points = benefit(unconverted, 300, "POINT_ACCRUAL");
        jdbc.update("UPDATE payment_benefit SET krw_amount = NULL WHERE payment_benefit_id = ?", points);
        recommend(unconverted, card, "5000", null, null);

        mockMvc.perform(benefits(OCTOBER))
                .andExpect(jsonPath("$.data.differences[*].paymentId").value(contains((int) userSelected, (int) limitPartial)))
                .andExpect(jsonPath("$.data.differences[0].reason").value("USER_SELECTED"))
                .andExpect(jsonPath("$.data.differences[0].expectedAmount").value(3_000))
                .andExpect(jsonPath("$.data.differences[0].actualAmount").value(1_000))
                .andExpect(jsonPath("$.data.differences[0].merchantName").value("스타벅스"))
                .andExpect(jsonPath("$.data.differences[0].userCardId").value(secondCard))
                .andExpect(jsonPath("$.data.differences[1].reason").value("LIMIT_PARTIAL"))
                .andExpect(jsonPath("$.data.summary.missedBenefit").value(3_500));
    }

    @Test
    void 야무픽_추가_혜택은_1순위로_승인한_건의_2순위와의_차이다() throws Exception {
        long pick = approved(owner, card, cafe, OCT_10, 10_000);
        benefit(pick, 1_500, "AT_APPROVAL");
        recommend(pick, card, "1500.5", secondCard, "300");      // 1,200.5 → 1,200
        long onlyOne = approved(owner, card, cafe, OCT_10.plusHours(1), 10_000);
        recommend(onlyOne, card, "800", null, null);            // 2순위 없음 → 세지 않음
        long notPick = approved(owner, secondCard, cafe, OCT_10.plusHours(2), 10_000);
        benefit(notPick, 300, "AT_APPROVAL");
        recommend(notPick, card, "1500", secondCard, "300");    // 1순위가 아님 → 세지 않음

        mockMvc.perform(benefits(OCTOBER))
                .andExpect(jsonPath("$.data.summary.yamuPickExtra").value(1_200));
    }

    // ── 픽스처 ──

    private MockHttpServletRequestBuilder monthly(String yearMonth) {
        return get("/api/reports/monthly").param("yearMonth", yearMonth).with(AuthUsers.asUser(owner));
    }

    private MockHttpServletRequestBuilder benefits(String yearMonth) {
        return get("/api/reports/monthly/benefits").param("yearMonth", yearMonth).with(AuthUsers.asUser(owner));
    }

    private long merchant(String name, String category) {
        return insert("merchant", "merchant_id", timestamps(Map.of("merchant_name", name,
                "brand_code", "BRAND-" + UUID.randomUUID(), "merchant_category_code", category)));
    }

    private void living(long paymentId) {
        jdbc.update("UPDATE payment_transaction SET spending_class = 'LIVING', spending_class_source = 'USER' "
                + "WHERE payment_transaction_id = ?", paymentId);
    }

    private void recommend(long paymentId, long bestCard, Object bestNet, Long secondCard, Object secondNet) {
        String first = "{\"rank\":1,\"card\":{\"userCardId\":%d},\"netBenefit\":%s}".formatted(bestCard, bestNet);
        String second = secondCard == null ? ""
                : ",{\"rank\":2,\"card\":{\"userCardId\":%d},\"netBenefit\":%s}".formatted(secondCard, secondNet);
        jdbc.update("UPDATE payment_transaction SET recommendation_result_json = ? WHERE payment_transaction_id = ?",
                "{\"schemaVersion\":1,\"candidates\":[" + first + second + "]}", paymentId);
    }

    private long approved(long user, long userCard, long merchantId, LocalDateTime requestedAt, long amount) {
        return payment(user, userCard, merchantId, "APPROVED", requestedAt, amount, "YAMU");
    }

    /** 승인·취소 상태면 승인 칸(DB CHECK ck_payment_approval)을 함께 채운다. 스냅샷 없이 가맹점 표의 이름·업종을 쓴다. */
    private long payment(long user, long userCard, long merchantId, String status, LocalDateTime requestedAt,
            long amount, String source) {
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", user, "merchant_id", merchantId,
                "payment_channel", "OFFLINE", "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", randomHash(), "source", source, "status", status,
                "requested_amount", amount, "installment_months", 1, "requested_at", requestedAt)));
        row.put("user_card_id", userCard);
        row.put("card_product_id", product);
        if (status.equals("APPROVED") || status.equals("CANCELED")) {
            row.put("approved_at", requestedAt.plusSeconds(2));
            row.put("approved_amount", amount);
            row.put("recognized_performance_amount", amount);
            row.put("performance_month", LocalDate.of(requestedAt.getYear(), requestedAt.getMonth(), 1));
            // 요청 시 분류기가 손대지 않게 미리 분류해 둔다(기본은 실적 제외 아님·생활 아님)
            row.put("spending_class", "EXCLUDED");
            row.put("spending_class_source", "USER");
        }
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private long benefit(long paymentId, long krw, String timing) {
        long cardBenefit = insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "B-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", "혜택",
                "benefit_type", "DISCOUNT", "calculation_type", "FIXED", "fixed_value", krw, "value_unit", "KRW",
                "application_timing", timing, "created_at", now()));
        return insert("payment_benefit", "payment_benefit_id", Map.of("payment_transaction_id", paymentId,
                "card_product_id", product, "card_benefit_id", cardBenefit, "benefit_value", krw, "value_unit", "KRW",
                "krw_amount", krw, "created_at", now()));
    }

    private Map<String, Object> timestamps(Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.put("created_at", now());
        row.put("updated_at", now());
        return row;
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static byte[] randomHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
