package com.yamu.be.payment.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
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
 * 결제 내역 목록·상세를 인증 필터·실제 MySQL까지 묶어 확인한다. 결제·청구·혜택 행은 승인 API 없이 SQL로 바로 넣는다.
 * 테스트마다 트랜잭션을 되돌리므로 요청과 데이터가 같은 연결·트랜잭션을 쓴다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-history-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentHistoryIntegrationTest extends MySqlTestSupport {

    static final String OCTOBER = "2026-10";
    static final LocalDateTime OCT_10 = LocalDateTime.of(2026, 10, 10, 3, 0);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    long product;
    long card;
    long otherCard;
    long merchant;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        product = fixtures.product();
        card = fixtures.card(owner, product, null, "ACTIVE");
        jdbc.update("UPDATE user_card SET card_alias = '메인 신용카드' WHERE user_card_id = ?", card);
        otherCard = fixtures.card(other, product, null, "ACTIVE");
        merchant = insert("merchant", "merchant_id", timestamps(Map.of("merchant_name", "스타벅스",
                "brand_code", "BRAND-" + UUID.randomUUID(), "merchant_category_code", "F01-CAFE")));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void 결제가_없으면_빈_목록과_0원_합계다() throws Exception {
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.summary.count").value(0))
                .andExpect(jsonPath("$.data.summary.amount").value(0))
                .andExpect(jsonPath("$.data.summary.confirmedBenefit").value(nullValue()));
    }

    @Test
    void 기본_상태는_승인과_취소이고_합계_금액은_승인만_더한다() throws Exception {
        long approved = approved(owner, card, OCT_10, 10_000, 1);
        long canceled = approved(owner, card, OCT_10.plusHours(1), 20_000, 1);
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(2), canceled);
        declined(owner, card, OCT_10.plusHours(3));
        payment(owner, null, "REQUESTED", OCT_10.plusHours(4), 5_000, 1, "YAMU");
        payment(owner, null, "EXPIRED", OCT_10.plusHours(5), 5_000, 1, "YAMU");
        benefit(approved, 1_000);
        benefit(canceled, 2_000);

        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) canceled, (int) approved)))
                .andExpect(jsonPath("$.data.summary.count").value(2))
                .andExpect(jsonPath("$.data.summary.amount").value(10_000))
                .andExpect(jsonPath("$.data.summary.expectedBenefit").value(1_000))
                .andExpect(jsonPath("$.data.items[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.items[1].approvedAt", endsWith("Z")))
                .andExpect(jsonPath("$.data.items[1].merchant.name").value("스타벅스 역삼점"))
                .andExpect(jsonPath("$.data.items[1].merchant.categoryCode").value("F01-CAFE"))
                .andExpect(jsonPath("$.data.items[1].card.userCardId").value(card))
                .andExpect(jsonPath("$.data.items[1].card.nickname").value("메인 신용카드"))
                .andExpect(jsonPath("$.data.items[1].card.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.items[1].plan").value("LUMP_SUM"))
                .andExpect(jsonPath("$.data.items[1].installmentMonths").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].benefitAmount").value(1_000))
                .andExpect(jsonPath("$.data.items[1].performanceRecognized").value(true))
                .andExpect(jsonPath("$.data.items[0].performanceRecognized").value(false));
    }

    @Test
    void 상태_출처_카드로_거른다() throws Exception {
        long declined = declined(owner, card, OCT_10);
        long seed = payment(owner, card, "APPROVED", OCT_10.plusHours(1), 7_000, 1, "SEED");
        long secondCard = fixtures.card(owner, product, null, "ACTIVE");
        long onSecond = approved(owner, secondCard, OCT_10.plusHours(2), 3_000, 1);

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("status", "DECLINED"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) declined)))
                .andExpect(jsonPath("$.data.items[0].approvedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].requestedAt", endsWith("Z")))
                .andExpect(jsonPath("$.data.summary.amount").value(0));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("source", "SEED"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) seed)));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("userCardId", String.valueOf(secondCard)))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) onSecond)));
    }

    @Test
    void 남의_결제는_목록에_없고_남의_카드로_거르면_빈_목록이다() throws Exception {
        approved(other, otherCard, OCT_10, 10_000, 1);

        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("userCardId", String.valueOf(otherCard)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 기간은_한국_날짜로_자른다() throws Exception {
        // 11월 1일 00:30(한국)은 UTC로 10월 31일 15:30이다
        long novemberKst = approved(owner, card, LocalDateTime.of(2026, 10, 31, 15, 30), 1_000, 1);
        long octoberKst = approved(owner, card, LocalDateTime.of(2026, 10, 31, 14, 59), 2_000, 1);

        mockMvc.perform(list().param("yearMonth", "2026-11"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) novemberKst)));
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) octoberKst)));
        mockMvc.perform(list().param("from", "2026-10-31").param("to", "2026-10-31"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) octoberKst)));
        mockMvc.perform(list().param("from", "2026-11-01"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) novemberKst)));
    }

    @Test
    void 기간을_주지_않으면_이번_달이다() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        long thisMonth = approved(owner, card, now, 1_000, 1);
        YearMonth current = YearMonth.now(ZoneId.of("Asia/Seoul"));
        approved(owner, card, current.minusMonths(1).atDay(10).atStartOfDay(), 2_000, 1);

        mockMvc.perform(list())
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) thisMonth)));
    }

    @Test
    void 페이지를_나누고_같은_시각은_결제_번호_역순이다() throws Exception {
        long[] ids = new long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = approved(owner, card, OCT_10, 1_000, 1);
        }

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) ids[4], (int) ids[3])))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("size", "2").param("page", "3"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) ids[0])))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.summary.count").value(5));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("size", "2").param("page", "4"))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void 잘못된_조회_조건은_400이다() throws Exception {
        expectInvalid(list().param("page", "0"), "page");
        expectInvalid(list().param("size", "101"), "size");
        expectInvalid(list().param("size", "0"), "size");
        expectInvalid(list().param("yearMonth", OCTOBER).param("from", "2026-10-01"), "yearMonth");
        expectInvalid(list().param("from", "2026-10-02").param("to", "2026-10-01"), "from");
        expectInvalid(list().param("status", "REQUESTED"), "status");
        mockMvc.perform(list().param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(list().param("yearMonth", "2026-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void 목록은_건수와_상관없이_같은_수의_쿼리로_읽는다() throws Exception {
        approved(owner, card, OCT_10, 1_000, 1);
        long one = questionsDuring(list().param("yearMonth", OCTOBER));

        for (int i = 0; i < 20; i++) {
            long id = approved(owner, card, OCT_10.plusMinutes(i), 1_000, 1);
            benefit(id, 100);
        }
        long many = questionsDuring(list().param("yearMonth", OCTOBER));

        // 합계 1번 + 페이지 1번(+ 측정 문장). 0이면 다른 연결을 재고 있는 것이다
        assertThat(one).isBetween(2L, 4L);
        assertThat(many).isEqualTo(one);
    }

    @Test
    void 상세는_혜택_한도_청구_회차_환급을_함께_보여준다() throws Exception {
        long id = approved(owner, card, OCT_10, 100_000, 3);
        long benefitRow = benefit(id, 3_000);
        long limitGroup = insert("limit_group", "limit_group_id", Map.of("card_product_id", product,
                "group_code", "LIFE_SHARED", "policy_version", 1, "group_name", "생활 통합 할인",
                "period_type", "MONTHLY", "limit_unit", "KRW", "base_limit_value", 10_000, "created_at", now()));
        insert("payment_limit_usage", "payment_limit_usage_id", timestamps(Map.of("payment_transaction_id", id,
                "card_product_id", product, "limit_group_id", limitGroup, "remaining_before", 10_000,
                "consumed_value", 3_000, "usage_date", LocalDate.of(2026, 10, 10),
                "usage_month", LocalDate.of(2026, 10, 1))));
        long bill = insert("card_bill", "card_bill_id", timestamps(Map.of("user_id", owner, "user_card_id", card,
                "billing_month", LocalDate.of(2026, 11, 1), "period_start", LocalDate.of(2026, 10, 1),
                "period_end", LocalDate.of(2026, 10, 31), "withdrawal_date", LocalDate.of(2026, 11, 16),
                "status", "ACCUMULATING")));
        long first = billItem(id, bill, 1, LocalDate.of(2026, 11, 1), 33_333, "BILLED");
        billItem(id, null, 2, LocalDate.of(2026, 12, 1), 33_333, "PENDING");
        billItem(id, null, 3, LocalDate.of(2027, 1, 1), 33_334, "PENDING");
        insert("refund", "refund_id", timestamps(Map.of("payment_transaction_id", id, "bill_item_id", first,
                "user_id", owner, "request_key", UUID.randomUUID().toString(), "principal_amount", 33_333,
                "fee_amount", 0, "refund_amount", 33_333, "status", "PENDING")));

        mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(id))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.channel").value("OFFLINE"))
                .andExpect(jsonPath("$.data.amount").value(100_000))
                .andExpect(jsonPath("$.data.chargedAmount").value(100_000))
                .andExpect(jsonPath("$.data.plan").value("INSTALLMENT"))
                .andExpect(jsonPath("$.data.installmentMonths").value(3))
                .andExpect(jsonPath("$.data.approvalNo").value("Y20261010" + String.format("%010d", id)))
                .andExpect(jsonPath("$.data.selectedCard.maskedNumber").value("****-****-****-1234"))
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(nullValue()))
                .andExpect(jsonPath("$.data.benefits[0].paymentBenefitId").value(benefitRow))
                .andExpect(jsonPath("$.data.benefits[0].name").value("카페 10% 청구할인"))
                .andExpect(jsonPath("$.data.benefits[0].type").value("DISCOUNT"))
                .andExpect(jsonPath("$.data.benefits[0].actualAmount").value(3_000))
                .andExpect(jsonPath("$.data.benefits[0].realization").value("AT_BILLING"))
                .andExpect(jsonPath("$.data.benefits[0].confirmed").value(nullValue()))
                .andExpect(jsonPath("$.data.performance.yearMonth").value(OCTOBER))
                .andExpect(jsonPath("$.data.performance.recognizedAmount").value(100_000))
                .andExpect(jsonPath("$.data.performance.recognized").value(true))
                .andExpect(jsonPath("$.data.limitImpacts[0].name").value("생활 통합 할인"))
                .andExpect(jsonPath("$.data.limitImpacts[0].period").value("MONTHLY"))
                .andExpect(jsonPath("$.data.limitImpacts[0].yearMonth").value(OCTOBER))
                .andExpect(jsonPath("$.data.limitImpacts[0].consumedAmount").value(3_000))
                .andExpect(jsonPath("$.data.billItems[*].seq").value(contains(1, 2, 3)))
                .andExpect(jsonPath("$.data.billItems[0].billId").value(bill))
                .andExpect(jsonPath("$.data.billItems[0].billingMonth").value("2026-11"))
                .andExpect(jsonPath("$.data.billItems[0].withdrawalDate").value("2026-11-16"))
                .andExpect(jsonPath("$.data.billItems[2].withdrawalDate").value(nullValue()))
                .andExpect(jsonPath("$.data.billItems[2].principalAmount").value(33_334))
                .andExpect(jsonPath("$.data.billItems[2].feeAmount").value(0))
                .andExpect(jsonPath("$.data.refunds[0].refundAmount").value(33_333))
                .andExpect(jsonPath("$.data.installmentPolicy").value("YAMU_INTEREST_FREE"))
                .andExpect(jsonPath("$.data.statusHistory[*].status").value(contains("REQUESTED", "APPROVED")))
                .andExpect(jsonPath("$.data.recommendationSnapshot").value(nullValue()))
                .andExpect(jsonPath("$.data.failure").value(nullValue()));
    }

    @Test
    void 거절_상세는_사유와_마지막_상태를_보여준다() throws Exception {
        long id = declined(owner, card, OCT_10);

        mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"))
                .andExpect(jsonPath("$.data.approvalNo").value(nullValue()))
                .andExpect(jsonPath("$.data.chargedAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.failure.code").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.failure.message").value("연결 계좌 잔액이 부족합니다."))
                .andExpect(jsonPath("$.data.statusHistory[*].status").value(contains("REQUESTED", "DECLINED")))
                .andExpect(jsonPath("$.data.statusHistory[1].at").value(nullValue()))
                .andExpect(jsonPath("$.data.billItems", hasSize(0)))
                .andExpect(jsonPath("$.data.installmentPolicy").value(nullValue()));
    }

    @Test
    void 남의_결제와_없는_결제는_같은_404다() throws Exception {
        long others = approved(other, otherCard, OCT_10, 10_000, 1);

        mockMvc.perform(get("/api/payments/" + others).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/payments/" + (others + 1000)).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private MockHttpServletRequestBuilder list() {
        return get("/api/payments").with(AuthUsers.asUser(owner));
    }

    private void expectInvalid(MockHttpServletRequestBuilder request, String field) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value(field));
    }

    /** 이 요청이 같은 연결에서 MySQL로 보낸 문장 수. 테스트 트랜잭션이 연결을 잡고 있어 요청도 같은 연결을 쓴다. */
    private long questionsDuring(MockHttpServletRequestBuilder request) throws Exception {
        long before = questions();
        mockMvc.perform(request).andExpect(status().isOk());
        return questions() - before;
    }

    private long questions() {
        return jdbc.query("SHOW SESSION STATUS LIKE 'Questions'", rs -> {
            rs.next();
            return rs.getLong(2);
        });
    }

    private long approved(long user, long userCard, LocalDateTime requestedAt, long amount, int months) {
        return payment(user, userCard, "APPROVED", requestedAt, amount, months, "YAMU");
    }

    private long declined(long user, long userCard, LocalDateTime requestedAt) {
        long id = payment(user, userCard, "DECLINED", requestedAt, 10_000, 1, "YAMU");
        jdbc.update("UPDATE payment_transaction SET failure_code = 'INSUFFICIENT_BALANCE' WHERE payment_transaction_id = ?",
                id);
        return id;
    }

    /** 승인·취소 상태면 승인 칸(DB CHECK ck_payment_approval)을 함께 채운다. 승인은 요청 2초 뒤다. */
    private long payment(long user, Long userCard, String status, LocalDateTime requestedAt, long amount, int months,
            String source) {
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", user, "merchant_id", merchant,
                "payment_channel", "OFFLINE", "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", randomHash(), "source", source, "status", status,
                "requested_amount", amount, "installment_months", months, "requested_at", requestedAt)));
        row.put("merchant_context_snapshot", """
                {"version":1,"merchantName":"스타벅스 역삼점","categoryCode":"F01-CAFE","channel":"OFFLINE","amount":%d}
                """.formatted(amount));
        if (userCard != null) {
            row.put("user_card_id", userCard);
            row.put("card_product_id", product);
        }
        if (status.equals("APPROVED") || status.equals("CANCELED")) {
            row.put("approved_at", requestedAt.plusSeconds(2));
            row.put("approved_amount", amount);
            row.put("recognized_performance_amount", amount);
            row.put("performance_month", LocalDate.of(requestedAt.getYear(), requestedAt.getMonth(), 1));
        }
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private long benefit(long paymentId, long krw) {
        long cardBenefit = insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "CAFE-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", "카페 10% 청구할인",
                "benefit_type", "DISCOUNT", "calculation_type", "FIXED", "fixed_value", krw, "value_unit", "KRW",
                "application_timing", "AT_BILLING", "created_at", now()));
        return insert("payment_benefit", "payment_benefit_id", Map.of("payment_transaction_id", paymentId,
                "card_product_id", product, "card_benefit_id", cardBenefit, "benefit_value", krw, "value_unit", "KRW",
                "krw_amount", krw, "created_at", now()));
    }

    private long billItem(long paymentId, Long bill, int seq, LocalDate billingMonth, long principal, String status) {
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("payment_transaction_id", paymentId,
                "user_id", owner, "user_card_id", card, "installment_no", seq, "billing_month", billingMonth,
                "principal_amount", principal, "fee_amount", 0, "billed_amount", principal, "status", status)));
        if (bill != null) {
            row.put("card_bill_id", bill);
        }
        return insert("bill_item", "bill_item_id", row);
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
