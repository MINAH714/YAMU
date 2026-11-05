package com.yamu.be.payment.billing.query;

import static org.hamcrest.Matchers.contains;
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
 * 환급 내역 조회(Task 5.4.8)를 인증 필터·실제 MySQL까지 묶어 확인한다. 결제·회차·청구서·환급 행은 SQL로 넣고 테스트마다 되돌린다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-refund-query-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundQueryIntegrationTest extends MySqlTestSupport {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    long account;
    long creditProduct;
    long checkProduct;
    long creditCard;
    long checkCard;
    long merchant;
    LocalDateTime now;
    int billSeq;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        account = fixtures.account(owner);
        creditProduct = fixtures.product();
        checkProduct = fixtures.product();
        jdbc.update("UPDATE card_product SET card_type = 'CHECK' WHERE card_product_id = ?", checkProduct);
        creditCard = fixtures.card(owner, creditProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET card_alias = '메인 신용카드' WHERE user_card_id = ?", creditCard);
        checkCard = fixtures.card(owner, checkProduct, account, "ACTIVE");
        merchant = insert("merchant", "merchant_id", timestamps(Map.of("merchant_name", "스타벅스",
                "brand_code", "REFUND-" + UUID.randomUUID(), "merchant_category_code", "F01-CAFE")));
    }

    @Test
    void 신용카드_환급과_체크카드_취소_환불을_최신순으로_보이고_대기_합계를_낸다() throws Exception {
        long bill = bill(owner, creditCard, "CONFIRMED", LocalDate.of(2026, 12, 14));
        long pending = refund(owner, payment(owner, creditCard, creditProduct, "CANCELED", 30_000), bill, 27_000,
                "PENDING", null, now.minusDays(1));
        long paidBill = bill(owner, creditCard, "PAID", LocalDate.of(2026, 11, 16));
        long completed = refund(owner, payment(owner, creditCard, creditProduct, "CANCELED", 10_000), paidBill, 10_000,
                "COMPLETED", LocalDate.of(2026, 11, 19), now.minusDays(3));
        long checkPayment = payment(owner, checkCard, checkProduct, "CANCELED", 14_250);
        jdbc.update("UPDATE payment_transaction SET canceled_at = ? WHERE payment_transaction_id = ?", now.minusDays(2),
                checkPayment);
        // 남의 환급은 보이지 않는다
        long otherAccount = fixtures.account(other);
        long otherCard = fixtures.card(other, creditProduct, otherAccount, "ACTIVE");
        refund(other, payment(other, otherCard, creditProduct, "CANCELED", 5_000), bill(other, otherCard, "CONFIRMED",
                LocalDate.of(2026, 12, 14)), 5_000, "PENDING", null, now);

        mockMvc.perform(refunds())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.pendingAmount").value(27_000))
                .andExpect(jsonPath("$.data.summary.pendingCount").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[*].reason")
                        .value(contains("CANCEL_AFTER_CONFIRM", "CHECK_CANCEL", "CANCEL_AFTER_CONFIRM")))
                // 출금 전 청구서의 환급: 기한 없음, 출금 예정일 이후
                .andExpect(jsonPath("$.data.items[0].refundId").value(pending))
                .andExpect(jsonPath("$.data.items[0].amount").value(27_000))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].billId").value(bill))
                .andExpect(jsonPath("$.data.items[0].scheduledAfter").value("2026-12-14"))
                .andExpect(jsonPath("$.data.items[0].dueDate").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].nickname").value("메인 신용카드"))
                .andExpect(jsonPath("$.data.items[0].merchantName").value("스타벅스 역삼점"))
                .andExpect(jsonPath("$.data.items[0].toAccount.accountId").value(account))
                .andExpect(jsonPath("$.data.items[0].toAccount.maskedAccountNo").value("****-****-****-5934"))
                // 체크카드 취소 환불: 환급 행 없음
                .andExpect(jsonPath("$.data.items[1].refundId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].billItemId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].paymentId").value(checkPayment))
                .andExpect(jsonPath("$.data.items[1].amount").value(14_250))
                .andExpect(jsonPath("$.data.items[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[1].dueDate")
                        .value(now.minusDays(2).atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate().toString()))
                .andExpect(jsonPath("$.data.items[1].completedAt").isString())
                // 출금 뒤 완료된 환급
                .andExpect(jsonPath("$.data.items[2].refundId").value(completed))
                .andExpect(jsonPath("$.data.items[2].scheduledAfter").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].dueDate").value("2026-11-19"));
    }

    @Test
    void 상태로_거르면_체크카드_환불은_COMPLETED에만_나온다() throws Exception {
        long bill = bill(owner, creditCard, "CONFIRMED", LocalDate.of(2026, 12, 14));
        refund(owner, payment(owner, creditCard, creditProduct, "CANCELED", 30_000), bill, 30_000, "PENDING", null,
                now.minusDays(1));
        long checkPayment = payment(owner, checkCard, checkProduct, "CANCELED", 14_250);
        jdbc.update("UPDATE payment_transaction SET canceled_at = ? WHERE payment_transaction_id = ?", now.minusDays(1),
                checkPayment);

        mockMvc.perform(refunds().param("status", "PENDING"))
                .andExpect(jsonPath("$.data.items[*].reason").value(contains("CANCEL_AFTER_CONFIRM")));
        mockMvc.perform(refunds().param("status", "COMPLETED"))
                .andExpect(jsonPath("$.data.items[*].reason").value(contains("CHECK_CANCEL")));
    }

    @Test
    void 기본_기간은_최근_3개월이고_기간을_주면_그_생성일로_거른다() throws Exception {
        long bill = bill(owner, creditCard, "PAID", LocalDate.of(2026, 11, 16));
        refund(owner, payment(owner, creditCard, creditProduct, "CANCELED", 30_000), bill, 30_000, "COMPLETED",
                LocalDate.of(2026, 11, 19), now.minusMonths(4));
        refund(owner, payment(owner, creditCard, creditProduct, "CANCELED", 10_000), bill, 10_000, "COMPLETED",
                LocalDate.of(2026, 11, 19), now.minusDays(1));

        mockMvc.perform(refunds()).andExpect(jsonPath("$.data.items", hasSize(1)));
        LocalDate fourMonthsAgo = now.minusMonths(4).atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
        mockMvc.perform(refunds().param("from", fourMonthsAgo.toString()).param("to", fourMonthsAgo.toString()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].amount").value(30_000));
    }

    @Test
    void 영원_승인과_등록_전_거래의_취소는_환불로_보이지_않는다() throws Exception {
        long zero = payment(owner, checkCard, checkProduct, "CANCELED", 10_000);
        jdbc.update("UPDATE payment_transaction SET approved_amount = 0, canceled_at = ? WHERE payment_transaction_id = ?",
                now.minusDays(1), zero);
        long seed = payment(owner, checkCard, checkProduct, "CANCELED", 10_000);
        jdbc.update("UPDATE payment_transaction SET source = 'SEED', canceled_at = ? WHERE payment_transaction_id = ?",
                now.minusDays(1), seed);

        mockMvc.perform(refunds()).andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 잘못된_조건은_400이고_토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(refunds().param("from", "2026-11-02").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("from"));
        mockMvc.perform(refunds().param("status", "DONE")).andExpect(status().isBadRequest());
        mockMvc.perform(refunds().param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/refunds")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder refunds() {
        return get("/api/refunds").with(AuthUsers.asUser(owner));
    }

    /** 승인된 뒤 취소된(또는 그 밖의 상태) 결제. 취소 시각은 따로 넣는다. */
    private long payment(long user, long userCard, long product, String status, long amount) {
        LocalDateTime requestedAt = now.minusDays(10);
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", user, "merchant_id", merchant,
                "request_key", UUID.randomUUID().toString(), "request_fingerprint", randomHash(), "source", "YAMU",
                "status", status, "requested_amount", amount, "installment_months", 1, "requested_at", requestedAt)));
        row.put("merchant_context_snapshot", """
                {"version":1,"merchantName":"스타벅스 역삼점","categoryCode":"F01-CAFE","channel":"OFFLINE","amount":%d}
                """.formatted(amount));
        row.put("user_card_id", userCard);
        row.put("card_product_id", product);
        row.put("approved_at", requestedAt.plusSeconds(2));
        row.put("approved_amount", amount);
        row.put("recognized_performance_amount", 0L);
        row.put("performance_month", LocalDate.of(requestedAt.getYear(), requestedAt.getMonth(), 1));
        if ("CANCELED".equals(status)) {
            row.put("canceled_at", requestedAt.plusDays(1));
        }
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private long bill(long user, long userCard, String status, LocalDate withdrawalDate) {
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", user, "user_card_id", userCard,
                "billing_month", withdrawalDate.withDayOfMonth(1), "period_start", withdrawalDate.minusMonths(1).minusDays(28),
                "period_end", withdrawalDate.minusDays(28), "withdrawal_date", withdrawalDate, "status", status)));
        if (!"ACCUMULATING".equals(status)) {
            row.put("confirmed_amount", 100_000L);
            row.put("linked_account_id", jdbc.queryForObject(
                    "SELECT linked_account_id FROM user_card WHERE user_card_id = ?", Long.class, userCard));
            row.put("confirmed_at", now.minusDays(20));
        }
        if ("PAID".equals(status)) {
            row.put("paid_amount", 100_000L);
            row.put("paid_at", now.minusDays(15));
        }
        // 같은 카드·월 청구서(uk_bill_card_month)가 겹치지 않게 청구 월을 하나씩 늘린다
        row.put("billing_month", LocalDate.of(2030, 1, 1).plusMonths(billSeq++));
        return insert("card_bill", "card_bill_id", row);
    }

    private long refund(long user, long payment, long bill, long amount, String status, LocalDate due,
            LocalDateTime createdAt) {
        long userCard = jdbc.queryForObject("SELECT user_card_id FROM payment_transaction WHERE payment_transaction_id = ?",
                Long.class, payment);
        long item = insert("bill_item", "bill_item_id", timestamps(Map.of("payment_transaction_id", payment,
                "user_id", user, "user_card_id", userCard, "card_bill_id", bill, "installment_no", 1,
                "billing_month", LocalDate.of(2026, 11, 1), "principal_amount", amount, "fee_amount", 0L,
                "billed_amount", amount, "status", "COMPLETED".equals(status) ? "REFUNDED" : "REFUND_PENDING")));
        Map<String, Object> row = new HashMap<>(Map.of("payment_transaction_id", payment, "bill_item_id", item,
                "user_id", user, "linked_account_id", jdbc.queryForObject(
                        "SELECT linked_account_id FROM user_card WHERE user_card_id = ?", Long.class, userCard),
                "request_key", UUID.randomUUID().toString(), "principal_amount", amount, "fee_amount", 0L,
                "refund_amount", amount, "status", status));
        row.put("created_at", createdAt);
        row.put("updated_at", createdAt);
        if (due != null) {
            row.put("due_date", due);
        }
        if ("COMPLETED".equals(status)) {
            row.put("completed_at", createdAt.plusDays(1));
        }
        return insert("refund", "refund_id", row);
    }

    private Map<String, Object> timestamps(Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.put("created_at", now);
        row.put("updated_at", now);
        return row;
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static byte[] randomHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
