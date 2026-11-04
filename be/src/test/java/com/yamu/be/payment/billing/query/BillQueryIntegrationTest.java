package com.yamu.be.payment.billing.query;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.payment.billing.BillFixtures;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청구서 목록·상세 API를 인증 필터·실제 MySQL까지 묶어 확인한다(Task 5.4.1).
 *
 * <p>시계는 2026-11-30 15:30 UTC(한국 12/1 00:30)에 고정한다. 한국 기준으로는 12월이라 "최근 3개월"이 10~12월이고,
 * UTC 기준(11월)으로 잘못 계산하면 9월 청구서가 들어온다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-bill-query-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillQueryIntegrationTest extends MySqlTestSupport {

    private static final Instant NOW = Instant.parse("2026-11-30T15:30:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    OwnershipFixtures owners;
    BillFixtures bills;
    long user;
    long accountA;
    long accountB;
    long card;
    long checkCard;
    long merchant;
    long accumulating;
    long confirmed;
    long paidOctober;
    long paidSeptember;
    long unpaidJune;
    long canceledAugust;
    long installmentItem;
    long lumpSumItem;
    long excludedItem;

    @BeforeEach
    void setUp() {
        clock.reset();
        clock.advance(Duration.between(clock.instant(), NOW));
        owners = new OwnershipFixtures(jdbc);
        bills = new BillFixtures(jdbc);
        user = owners.user();
        accountA = owners.account(user);
        accountB = owners.account(user);
        // 카드는 지금 A 계좌. 12월 청구서는 B 계좌일 때 확정됐다
        card = owners.card(user, owners.product(), accountA, "ACTIVE");
        long checkProduct = owners.product();
        jdbc.update("UPDATE card_product SET card_type = 'CHECK', billing_cutoff_days = NULL WHERE card_product_id = ?",
                checkProduct);
        checkCard = owners.card(user, checkProduct, accountA, "ACTIVE");
        merchant = bills.merchant("이마트");

        YearMonth january = YearMonth.of(2027, 1);
        accumulating = bills.accumulating(user, card, january, LocalDate.of(2027, 1, 15));
        long lumpSum = bills.payment(user, card, merchant, 30_000L, 1, LocalDateTime.of(2026, 12, 3, 1, 0));
        jdbc.update("UPDATE payment_transaction SET merchant_context_snapshot = ? WHERE payment_transaction_id = ?",
                "{\"merchantName\": \"스타벅스 역삼점\"}", lumpSum);
        lumpSumItem = bills.item(lumpSum, user, card, accumulating, 1, january, 30_000L, "PENDING");
        long installment = bills.payment(user, card, merchant, 90_000L, 3, LocalDateTime.of(2026, 12, 5, 1, 0));
        installmentItem = bills.item(installment, user, card, accumulating, 1, january, 30_000L, "PENDING");
        long canceled = bills.payment(user, card, merchant, 5_000L, 1, LocalDateTime.of(2026, 12, 6, 1, 0));
        excludedItem = bills.item(canceled, user, card, accumulating, 1, january, 5_000L, "EXCLUDED");

        confirmed = bills.confirmed(user, card, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 15), accountB, 50_000L);
        paidOctober = bills.paid(user, card, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 15), accountA, 30_000L,
                "20261015070012000001");
        long refunded = bills.payment(user, card, merchant, 30_000L, 1, LocalDateTime.of(2026, 9, 20, 1, 0));
        long refundedItem = bills.item(refunded, user, card, paidOctober, 1, YearMonth.of(2026, 10), 30_000L,
                "REFUNDED");
        bills.refund(refunded, refundedItem, user, accountA, 30_000L, "COMPLETED");
        paidSeptember = bills.paid(user, card, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 15), accountA, 10_000L,
                "20260915070012000001");
        canceledAugust = bills.unpaid(user, card, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 17), accountA, 8_000L,
                8_000L);
        unpaidJune = bills.unpaid(user, card, YearMonth.of(2026, 6), LocalDate.of(2026, 6, 15), accountA, 40_000L,
                15_000L);
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mockMvc.perform(get("/api/billings")).andExpect(status().isUnauthorized());
    }

    @Test
    void 청구_월을_고르지_않으면_끝나지_않은_청구서_전부와_한국_기준_최근_3개월을_보여준다() throws Exception {
        // 남의 청구서는 섞이지 않는다
        long stranger = owners.user();
        long strangerAccount = owners.account(stranger);
        long strangerCard = owners.card(stranger, owners.product(), strangerAccount, "ACTIVE");
        bills.confirmed(stranger, strangerCard, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 15), strangerAccount,
                99_000L);

        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                // 9월 출금 완료·8월 CANCELED는 10~12월 밖이라 빠지고, 6월 미납은 끝나지 않아 들어온다
                .andExpect(jsonPath("$.data.items[*].billId").value(contains(
                        (int) accumulating, (int) confirmed, (int) paidOctober, (int) unpaidJune)))
                .andExpect(jsonPath("$.data.summary.nextWithdrawal.billId").value(confirmed))
                .andExpect(jsonPath("$.data.summary.nextWithdrawal.date").value("2026-12-15"))
                .andExpect(jsonPath("$.data.summary.nextWithdrawal.expectedAmount").value(50_000))
                .andExpect(jsonPath("$.data.summary.unpaidCount").value(1));
    }

    @Test
    void 진행_중_청구서는_예상액과_카드의_지금_계좌를_확정_청구서는_확정액과_확정_당시_계좌를_보여준다() throws Exception {
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("ACCUMULATING"))
                .andExpect(jsonPath("$.data.items[0].billingMonth").value("2027-01"))
                .andExpect(jsonPath("$.data.items[0].usagePeriod.from").value("2026-12-02"))
                .andExpect(jsonPath("$.data.items[0].usagePeriod.to").value("2027-01-01"))
                .andExpect(jsonPath("$.data.items[0].closingDate").value("2027-01-01"))
                // 마감일 1/1 + 상품의 마감 일수 14일
                .andExpect(jsonPath("$.data.items[0].paymentDate").value("2027-01-15"))
                .andExpect(jsonPath("$.data.items[0].withdrawalDate").value("2027-01-15"))
                // 확정 전 취소로 빠진 5,000원은 건수·합계에 넣지 않는다
                .andExpect(jsonPath("$.data.items[0].itemCount").value(2))
                .andExpect(jsonPath("$.data.items[0].itemAmount").value(60_000))
                .andExpect(jsonPath("$.data.items[0].billingDiscount").value(0))
                .andExpect(jsonPath("$.data.items[0].expectedAmount").value(60_000))
                .andExpect(jsonPath("$.data.items[0].finalAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].withdrawalAccount.accountId").value(accountA))
                .andExpect(jsonPath("$.data.items[0].withdrawalAccount.maskedAccountNo").value("****-****-****-5934"))
                .andExpect(jsonPath("$.data.items[1].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[1].expectedAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].finalAmount").value(50_000))
                .andExpect(jsonPath("$.data.items[1].withdrawalAccount.accountId").value(accountB));
    }

    @Test
    void 청구_월과_상태로_거른다() throws Exception {
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("billingMonth", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].billId").value(contains((int) paidSeptember)));
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].billId").value(contains((int) paidOctober)));
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user))
                        .param("billingMonth", "2026-08").param("status", "CANCELED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].billId").value(contains((int) canceledAugust)));
    }

    @Test
    void 체크카드는_빈_목록이고_없는_카드와_남의_카드는_404다() throws Exception {
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("userCardId", String.valueOf(checkCard)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.summary.nextWithdrawal").value(nullValue()))
                .andExpect(jsonPath("$.data.summary.unpaidCount").value(0));

        long stranger = owners.user();
        long strangerCard = owners.card(stranger, owners.product(), owners.account(stranger), "ACTIVE");
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user))
                        .param("userCardId", String.valueOf(strangerCard)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("userCardId", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 카드로_거르면_요약도_그_카드만_본다() throws Exception {
        long otherCard = owners.card(user, owners.product(), accountA, "ACTIVE");
        bills.unpaid(user, otherCard, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 15), accountA, 7_000L, 0L);

        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("userCardId", String.valueOf(otherCard)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.summary.nextWithdrawal").value(nullValue()))
                .andExpect(jsonPath("$.data.summary.unpaidCount").value(1));
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.unpaidCount").value(2));
    }

    @Test
    void 형식이_틀린_청구_월과_상태는_400이다() throws Exception {
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("billingMonth", "2026-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)).param("status", "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void 진행_중_청구서_상세는_빠진_회차까지_회차별로_보여준다() throws Exception {
        mockMvc.perform(get("/api/billings/{billId}", accumulating).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskedNumber").value("****-****-****-1234"))
                .andExpect(jsonPath("$.data.amounts.itemAmount").value(60_000))
                .andExpect(jsonPath("$.data.amounts.billingDiscount").value(0))
                .andExpect(jsonPath("$.data.amounts.expectedAmount").value(60_000))
                .andExpect(jsonPath("$.data.amounts.finalAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.withdrawalAccount.accountId").value(accountA))
                // 승인 시각 늦은 순: 취소로 빠진 회차(12/6) → 할부(12/5) → 일시불(12/3)
                .andExpect(jsonPath("$.data.items[*].billItemId").value(contains(
                        (int) excludedItem, (int) installmentItem, (int) lumpSumItem)))
                .andExpect(jsonPath("$.data.items[0].status").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.items[1].plan").value("INSTALLMENT"))
                .andExpect(jsonPath("$.data.items[1].seq").value(1))
                .andExpect(jsonPath("$.data.items[1].totalSeq").value(3))
                .andExpect(jsonPath("$.data.items[1].amount").value(30_000))
                .andExpect(jsonPath("$.data.items[1].feeAmount").value(0))
                .andExpect(jsonPath("$.data.items[1].installmentPolicy").value("YAMU_INTEREST_FREE"))
                .andExpect(jsonPath("$.data.items[1].merchantName").value("이마트"))
                .andExpect(jsonPath("$.data.items[2].plan").value("LUMP_SUM"))
                .andExpect(jsonPath("$.data.items[2].totalSeq").value(1))
                .andExpect(jsonPath("$.data.items[2].installmentPolicy").value(nullValue()))
                // 승인 당시 기록한 가맹점 이름을 먼저 쓴다
                .andExpect(jsonPath("$.data.items[2].merchantName").value("스타벅스 역삼점"))
                .andExpect(jsonPath("$.data.discounts", hasSize(0)))
                .andExpect(jsonPath("$.data.withdrawal").value(nullValue()))
                .andExpect(jsonPath("$.data.refunds", hasSize(0)))
                .andExpect(jsonPath("$.data.history[*].status").value(contains("ACCUMULATING")));
    }

    @Test
    void 출금_완료_청구서_상세는_출금_결과와_환급과_상태_이력을_보여준다() throws Exception {
        mockMvc.perform(get("/api/billings/{billId}", paidOctober).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.amounts.finalAmount").value(30_000))
                .andExpect(jsonPath("$.data.amounts.expectedAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.withdrawal.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.withdrawal.finTransactionNo").value("20261015070012000001"))
                .andExpect(jsonPath("$.data.refunds", hasSize(1)))
                .andExpect(jsonPath("$.data.refunds[0].amount").value(30_000))
                .andExpect(jsonPath("$.data.refunds[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.history[*].status").value(contains("ACCUMULATING", "CONFIRMED", "PAID")));
    }

    @Test
    void 미납_청구서_상세는_실패_시각만_있고_결과와_사유는_비어_있다() throws Exception {
        mockMvc.perform(get("/api/billings/{billId}", unpaidJune).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andExpect(jsonPath("$.data.withdrawal.attemptedAt").exists())
                .andExpect(jsonPath("$.data.withdrawal.result").value(nullValue()))
                .andExpect(jsonPath("$.data.withdrawal.failureReason").value(nullValue()))
                .andExpect(jsonPath("$.data.history[*].status").value(contains("ACCUMULATING", "CONFIRMED", "UNPAID")));
    }

    @Test
    void 없는_청구서와_남의_청구서_상세는_404다() throws Exception {
        long stranger = owners.user();
        long strangerAccount = owners.account(stranger);
        long strangerCard = owners.card(stranger, owners.product(), strangerAccount, "ACTIVE");
        long strangerBill = bills.confirmed(stranger, strangerCard, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 15),
                strangerAccount, 99_000L);

        mockMvc.perform(get("/api/billings/{billId}", strangerBill).with(AuthUsers.asUser(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/billings/{billId}", 999_999_999L).with(AuthUsers.asUser(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
