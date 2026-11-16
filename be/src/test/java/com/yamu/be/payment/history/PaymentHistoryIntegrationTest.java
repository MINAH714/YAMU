package com.yamu.be.payment.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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
                .andExpect(jsonPath("$.data.summary.confirmedBenefit").value(0))
                .andExpect(jsonPath("$.data.summary.byClass.LIVING").value(0))
                .andExpect(jsonPath("$.data.summary.yamuPickCount").value(0));
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
                .andExpect(jsonPath("$.data.items[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.data.items[0].benefitAmount").value(0))
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
        // 처음 읽을 때는 미분류 결제를 분류하느라 문장이 더 나간다. 분류가 끝난 뒤의 읽기만 잰다
        mockMvc.perform(list().param("yearMonth", OCTOBER)).andExpect(status().isOk());
        long one = questionsDuring(list().param("yearMonth", OCTOBER));

        for (int i = 0; i < 20; i++) {
            long id = approved(owner, card, OCT_10.plusMinutes(i), 1_000, 1);
            benefit(id, 100);
        }
        mockMvc.perform(list().param("yearMonth", OCTOBER)).andExpect(status().isOk());
        long many = questionsDuring(list().param("yearMonth", OCTOBER));

        // 미분류 확인 1번 + 합계 1번 + 페이지 1번(+ 측정 문장). 0이면 다른 연결을 재고 있는 것이다
        assertThat(one).isBetween(3L, 5L);
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
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(nullValue()))
                .andExpect(jsonPath("$.data.merchant.categoryCode").value("F01-CAFE"))
                .andExpect(jsonPath("$.data.merchant.categoryName").value(nullValue()))
                .andExpect(jsonPath("$.data.benefits[0].paymentBenefitId").value(benefitRow))
                .andExpect(jsonPath("$.data.benefits[0].name").value("카페 10% 청구할인"))
                .andExpect(jsonPath("$.data.benefits[0].type").value("DISCOUNT"))
                .andExpect(jsonPath("$.data.benefits[0].actualAmount").value(3_000))
                .andExpect(jsonPath("$.data.benefits[0].rawAmount").value(3_000))
                .andExpect(jsonPath("$.data.benefits[0].unit").value("KRW"))
                .andExpect(jsonPath("$.data.benefits[0].realization").value("AT_BILLING"))
                .andExpect(jsonPath("$.data.benefits[0].confirmed").value(nullValue()))
                .andExpect(jsonPath("$.data.performance.yearMonth").value(OCTOBER))
                .andExpect(jsonPath("$.data.performance.recognizedAmount").value(100_000))
                .andExpect(jsonPath("$.data.performance.recognized").value(true))
                .andExpect(jsonPath("$.data.limitImpacts[0].name").value("생활 통합 할인"))
                .andExpect(jsonPath("$.data.limitImpacts[0].period").value("MONTHLY"))
                .andExpect(jsonPath("$.data.limitImpacts[0].yearMonth").value(OCTOBER))
                .andExpect(jsonPath("$.data.limitImpacts[0].consumedAmount").value(3_000))
                .andExpect(jsonPath("$.data.limitImpacts[0].unit").value("KRW"))
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
    void 상세의_적립은_포인트_수와_단위를_함께_주고_원화로_못_바꾼_줄은_actualAmount만_비운다() throws Exception {
        long id = approved(owner, card, OCT_10, 10_000, 1);
        pointBenefit(id, "1500.000000", 1_500L);
        pointBenefit(id, "3.000000", null);
        pointBenefit(id, "1.500000", null);

        mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.benefits[*].unit").value(contains("POINT", "POINT", "POINT")))
                .andExpect(jsonPath("$.data.benefits[0].rawAmount").value(1_500))
                .andExpect(jsonPath("$.data.benefits[0].actualAmount").value(1_500))
                .andExpect(jsonPath("$.data.benefits[1].rawAmount").value(3))
                .andExpect(jsonPath("$.data.benefits[1].actualAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.benefits[2].rawAmount").value(1.5))
                .andExpect(jsonPath("$.data.benefits[2].realization").value("POINT_ACCRUAL"));
        // 지수 표기(1.5E+3)가 아니라 정수 그대로 나간다
        String body = mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"rawAmount\":1500,").contains("\"rawAmount\":1.5,").doesNotContain("E+");
    }

    @Test
    void 상세와_목록은_저장된_추천_순위_직접_선택과_업종_이름을_보여준다() throws Exception {
        long id = approved(owner, card, OCT_10, 10_000, 1);
        jdbc.update("UPDATE payment_transaction SET recommended_rank = 2, user_selected = TRUE,"
                + " merchant_context_snapshot = JSON_SET(merchant_context_snapshot, '$.categoryCode', 'COFFEE_SHOP')"
                + " WHERE payment_transaction_id = ?", id);

        mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCard.recommendedRank").value(2))
                .andExpect(jsonPath("$.data.selectedCard.userSelected").value(true))
                .andExpect(jsonPath("$.data.merchant.categoryCode").value("COFFEE_SHOP"))
                .andExpect(jsonPath("$.data.merchant.categoryName").value("카페"));
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].merchant.categoryName").value("카페"));
    }

    @Test
    void 상세의_누적_실적은_그_실적_월의_지금_합계이고_취소된_결제는_비운다() throws Exception {
        long id = approved(owner, card, OCT_10, 10_000, 1);
        long canceled = approved(owner, card, OCT_10.plusHours(1), 20_000, 1);
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(2), canceled);
        // 그 뒤 다른 결제까지 더해진 10월 합계(결제 당시가 아니라 지금 값을 보인다)
        insert("card_monthly_performance", "card_monthly_performance_id", timestamps(Map.of("user_card_id", card,
                "performance_month", LocalDate.of(2026, 10, 1), "recognized_amount", 250_000L, "version", 0)));

        mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.performance.recognizedAmount").value(10_000))
                .andExpect(jsonPath("$.data.performance.recognizedTotalAfter").value(250_000))
                .andExpect(jsonPath("$.data.performance.targetAmount").value(nullValue()));
        mockMvc.perform(get("/api/payments/" + canceled).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.performance.recognizedTotalAfter").value(nullValue()))
                .andExpect(jsonPath("$.data.performance.targetAmount").value(nullValue()));
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
    void 상세는_목록과_같은_메모를_보여주고_메모가_없으면_null_칸이다() throws Exception {
        long withMemo = approved(owner, card, OCT_10, 12_000, 1);
        jdbc.update("UPDATE payment_transaction SET memo = '팀 회식 정산', memo_updated_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(1), withMemo);
        long withoutMemo = declined(owner, card, OCT_10.plusHours(2));

        mockMvc.perform(get("/api/payments/" + withMemo).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").value("팀 회식 정산"))
                .andExpect(jsonPath("$.data", not(hasKey("memoUpdatedAt"))));
        mockMvc.perform(get("/api/payments/" + withoutMemo).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasKey("memo")))
                .andExpect(jsonPath("$.data.memo").value(nullValue()));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("status", "APPROVED"))
                .andExpect(jsonPath("$.data.items[0].memo").value("팀 회식 정산"));
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

    // ───────── v0.3.1 보완: 검색·필터·칸 (Task 5.3.4) ─────────

    @Test
    void 검색어는_가맹점명과_메모를_부분_일치로_찾고_숫자만이면_금액도_찾는다() throws Exception {
        long memoHit = approved(owner, card, OCT_10, 12_000, 1);
        jdbc.update("UPDATE payment_transaction SET memo = '팀 회식 정산' WHERE payment_transaction_id = ?", memoHit);
        long amountHit = approved(owner, card, OCT_10.plusHours(1), 15_000, 1);
        approved(owner, card, OCT_10.plusHours(2), 9_000, 1);

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "  회식 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) memoHit)))
                .andExpect(jsonPath("$.data.items[0].memo").value("팀 회식 정산"));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "15,000"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) amountHit)));
        // 가맹점명(스냅샷 "스타벅스 역삼점") 부분 일치는 모두
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "역삼"))
                .andExpect(jsonPath("$.data.items", hasSize(3)));
        // LIKE 특수문자는 글자 그대로 찾는다
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "%"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 검색은_대소문자를_가리지_않고_밑줄은_글자_그대로_찾는다() throws Exception {
        long latin = approved(owner, card, OCT_10, 5_000, 1);
        jdbc.update("UPDATE payment_transaction SET merchant_context_snapshot = "
                + "JSON_SET(merchant_context_snapshot, '$.merchantName', 'Starbucks Gangnam') WHERE payment_transaction_id = ?",
                latin);
        long underscore = approved(owner, card, OCT_10.plusHours(1), 6_000, 1);
        jdbc.update("UPDATE payment_transaction SET memo = 'Team_A Dinner' WHERE payment_transaction_id = ?", underscore);
        long lookalike = approved(owner, card, OCT_10.plusHours(2), 7_000, 1);
        jdbc.update("UPDATE payment_transaction SET memo = 'teamXa dinner' WHERE payment_transaction_id = ?", lookalike);

        // 표 정렬 규칙(utf8mb4_0900_bin)은 대소문자를 가리지만 검색은 가리지 않는다(!142 리뷰)
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "starbucks"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) latin)));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "DINNER"))
                .andExpect(jsonPath("$.data.items", hasSize(2)));
        // _는 LIKE에서 아무 한 글자이지만 이스케이프해서 밑줄만 찾는다("teamXa"는 걸리지 않음)
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", "team_a"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) underscore)));
    }

    @Test
    void 검색어_길이는_코드포인트로_센다() throws Exception {
        String emoji = "😀";  // UTF-16 두 칸, 한 글자
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("q", emoji.repeat(50)))
                .andExpect(status().isOk());
        expectInvalid(list().param("yearMonth", OCTOBER).param("q", emoji.repeat(51)), "q");
    }

    // ───────── 소비 분류: 목록을 읽기 직전에 분류(2026-11-06 BE 4 결정) ─────────

    @Test
    void 예보를_열지_않은_사용자도_목록을_읽으면_미분류_결제가_분류된다() throws Exception {
        long living = approved(owner, card, OCT_10, 10_000, 1);
        long installment = approved(owner, card, OCT_10.plusHours(1), 600_000, 3);  // 할부라 LARGE
        long userChosen = approved(owner, card, OCT_10.plusHours(2), 8_000, 1);
        jdbc.update("UPDATE payment_transaction SET spending_class = 'LARGE', spending_class_source = 'USER' "
                + "WHERE payment_transaction_id = ?", userChosen);
        long canceled = payment(owner, card, "CANCELED", OCT_10.plusHours(3), 4_000, 1, "YAMU");

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("spendingClass", "LIVING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) living)))
                .andExpect(jsonPath("$.data.items[0].spendingClass").value("LIVING"));
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.summary.byClass.LIVING").value(10_000))
                .andExpect(jsonPath("$.data.summary.byClass.LARGE").value(608_000))
                .andExpect(jsonPath("$.data.summary.byClass.EXCLUDED").value(0));

        assertThat(spendingClass(installment)).isEqualTo("LARGE");
        // 사용자가 고친 값은 덮지 않고, 취소 결제는 분류하지 않는다
        assertThat(spendingClass(userChosen)).isEqualTo("LARGE");
        assertThat(jdbc.queryForObject("SELECT spending_class_source FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, userChosen)).isEqualTo("USER");
        assertThat(spendingClass(canceled)).isNull();
    }

    private String spendingClass(long paymentId) {
        return jdbc.queryForObject("SELECT spending_class FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, paymentId);
    }

    @Test
    void 업종_접두어_소비_분류_채널로_거른다() throws Exception {
        long living = approved(owner, card, OCT_10, 10_000, 1);
        jdbc.update("UPDATE payment_transaction SET spending_class = 'LIVING', spending_class_source = 'AUTO', payment_channel = 'ONLINE' "
                + "WHERE payment_transaction_id = ?", living);
        long large = approved(owner, card, OCT_10.plusHours(1), 300_000, 1);
        jdbc.update("UPDATE payment_transaction SET spending_class = 'LARGE', spending_class_source = 'AUTO' WHERE payment_transaction_id = ?", large);

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("categoryCode", "F01"))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].categoryCode").value("F01-CAFE"));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("categoryCode", "F02"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("spendingClass", "LIVING"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) living)))
                .andExpect(jsonPath("$.data.items[0].spendingClass").value("LIVING"));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("channel", "ONLINE"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) living)))
                .andExpect(jsonPath("$.data.items[0].channel").value("ONLINE"));
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.summary.byClass.LIVING").value(10_000))
                .andExpect(jsonPath("$.data.summary.byClass.LARGE").value(300_000))
                .andExpect(jsonPath("$.data.summary.byClass.EXCLUDED").value(0));
    }

    @Test
    void 혜택_상태는_즉시할인은_승인_때_청구할인은_회차가_모두_확정되면_확정이다() throws Exception {
        long instant = approved(owner, card, OCT_10, 10_000, 1);
        benefit(instant, 1_000, "AT_APPROVAL");
        long billingPending = approved(owner, card, OCT_10.plusHours(1), 20_000, 1);
        benefit(billingPending, 2_000, "AT_BILLING");
        billItem(billingPending, null, 1, LocalDate.of(2026, 11, 1), 20_000, "PENDING");
        long billingDone = approved(owner, card, OCT_10.plusHours(2), 30_000, 1);
        benefit(billingDone, 3_000, "AT_BILLING");
        billItem(billingDone, null, 1, LocalDate.of(2026, 11, 1), 30_000, "BILLED");
        long points = approved(owner, card, OCT_10.plusHours(3), 40_000, 1);
        benefit(points, 400, "POINT_ACCRUAL");
        long none = approved(owner, card, OCT_10.plusHours(4), 5_000, 1);

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("benefitStatus", "CONFIRMED"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) billingDone, (int) instant)))
                .andExpect(jsonPath("$.data.items[0].benefitConfirmed").value(true));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("benefitStatus", "EXPECTED"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) points, (int) billingPending)))
                .andExpect(jsonPath("$.data.items[0].benefitConfirmed").value(false));
        mockMvc.perform(list().param("yearMonth", OCTOBER).param("benefitStatus", "NONE"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) none)))
                .andExpect(jsonPath("$.data.items[0].benefitConfirmed").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].benefitStatus").value("NONE"));
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.summary.expectedBenefit").value(6_400))
                .andExpect(jsonPath("$.data.summary.confirmedBenefit").value(4_000));
    }

    @Test
    void 목록의_혜택_종류는_원화_금액이_가장_큰_혜택_줄의_종류다() throws Exception {
        // 시각이 늦을수록 목록 앞이라 아래에서 위 순서로 items[0..]가 된다
        long instant = approved(owner, card, OCT_10, 10_000, 1);
        typedBenefit(instant, "DISCOUNT", "AT_APPROVAL", 1_000, 1_000L);
        typedBenefit(instant, "POINT", "POINT_ACCRUAL", 500, 500L);
        long billing = approved(owner, card, OCT_10.plusHours(1), 10_000, 1);
        typedBenefit(billing, "DISCOUNT", "AT_BILLING", 2_000, 2_000L);
        long cashback = approved(owner, card, OCT_10.plusHours(2), 10_000, 1);
        typedBenefit(cashback, "DISCOUNT", "AT_BILLING", 1_000, 1_000L);
        typedBenefit(cashback, "CASHBACK", "AFTER_BILLING", 3_000, 3_000L);
        // 금액이 같으면 먼저 기록한 줄
        long tie = approved(owner, card, OCT_10.plusHours(3), 10_000, 1);
        typedBenefit(tie, "POINT", "POINT_ACCRUAL", 700, 700L);
        typedBenefit(tie, "DISCOUNT", "AT_APPROVAL", 700, 700L);
        // 원화로 바꾸지 못한 줄은 금액이 커 보여도 맨 뒤
        long unconverted = approved(owner, card, OCT_10.plusHours(4), 10_000, 1);
        typedBenefit(unconverted, "POINT", "POINT_ACCRUAL", 5_000, null);
        typedBenefit(unconverted, "DISCOUNT", "AT_BILLING", 100, 100L);
        // 시점을 모르는 할인은 시점을 지어내지 않는다
        long unknownTiming = approved(owner, card, OCT_10.plusHours(5), 10_000, 1);
        typedBenefit(unknownTiming, "DISCOUNT", null, 1_000, 1_000L);
        long none = approved(owner, card, OCT_10.plusHours(6), 10_000, 1);
        long canceled = approved(owner, card, OCT_10.plusHours(7), 10_000, 1);
        typedBenefit(canceled, "DISCOUNT", "AT_APPROVAL", 1_000, 1_000L);
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(8), canceled);

        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) canceled, (int) none,
                        (int) unknownTiming, (int) unconverted, (int) tie, (int) cashback, (int) billing, (int) instant)))
                .andExpect(jsonPath("$.data.items[0].benefitType").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].benefitType").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].benefitType").value(nullValue()))
                .andExpect(jsonPath("$.data.items[3].benefitType").value("BILLING_DISCOUNT"))
                .andExpect(jsonPath("$.data.items[4].benefitType").value("POINT"))
                .andExpect(jsonPath("$.data.items[5].benefitType").value("CASHBACK"))
                .andExpect(jsonPath("$.data.items[6].benefitType").value("BILLING_DISCOUNT"))
                .andExpect(jsonPath("$.data.items[7].benefitType").value("INSTANT_DISCOUNT"));
    }

    @Test
    void 목록과_상세의_카드는_카드_목록_API와_같은_카드사_표시값이다() throws Exception {
        long id = approved(owner, card, OCT_10, 10_000, 1);

        // 카드사 표에 없는 코드(픽스처 TEST)는 이름이 코드 그대로이고 색이 없다
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.items[0].card.issuer.code").value("TEST"))
                .andExpect(jsonPath("$.data.items[0].card.issuer.name").value("TEST"))
                .andExpect(jsonPath("$.data.items[0].card.issuer.color").value(nullValue()));

        jdbc.update("UPDATE card_product SET issuer_code = 'SHINHAN' WHERE card_product_id = ?", product);
        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.items[0].paymentId").value(id))
                .andExpect(jsonPath("$.data.items[0].card.issuer.code").value("SHINHAN"))
                .andExpect(jsonPath("$.data.items[0].card.issuer.name").value("신한카드"))
                .andExpect(jsonPath("$.data.items[0].card.issuer.color").value("#0046FF"));
        mockMvc.perform(get("/api/payments/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.selectedCard.issuer.code").value("SHINHAN"))
                .andExpect(jsonPath("$.data.selectedCard.issuer.name").value("신한카드"));
    }

    @Test
    void 취소된_결제는_혜택_기록이_있어도_NONE이다() throws Exception {
        long canceled = approved(owner, card, OCT_10, 10_000, 1);
        benefit(canceled, 1_000, "AT_APPROVAL");
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                OCT_10.plusHours(1), canceled);

        mockMvc.perform(list().param("yearMonth", OCTOBER).param("benefitStatus", "NONE"))
                .andExpect(jsonPath("$.data.items[*].paymentId").value(contains((int) canceled)));
    }

    @Test
    void 야무픽은_저장된_추천_1순위_카드로_승인한_건만_센다() throws Exception {
        long pick = approved(owner, card, OCT_10, 10_000, 1);
        long other = approved(owner, card, OCT_10.plusHours(1), 10_000, 1);
        approved(owner, card, OCT_10.plusHours(2), 10_000, 1);
        String result = "{\"schemaVersion\":1,\"candidates\":[{\"rank\":1,\"card\":{\"userCardId\":%d}},"
                + "{\"rank\":2,\"card\":{\"userCardId\":%d}}]}";
        jdbc.update("UPDATE payment_transaction SET recommendation_result_json = ? WHERE payment_transaction_id = ?",
                result.formatted(card, otherCard), pick);
        jdbc.update("UPDATE payment_transaction SET recommendation_result_json = ? WHERE payment_transaction_id = ?",
                result.formatted(otherCard, card), other);

        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.summary.yamuPickCount").value(1));
    }

    @Test
    void 야무픽은_승인_때_적은_순위가_있으면_저장된_추천_결과보다_그_순위를_따른다() throws Exception {
        String result = "{\"schemaVersion\":1,\"candidates\":[{\"rank\":1,\"card\":{\"userCardId\":%d}},"
                + "{\"rank\":2,\"card\":{\"userCardId\":%d}}]}";
        // 추천 결과로는 2순위지만 승인 때 다시 계산해 1순위로 승인함 → 센다
        long reRanked = approved(owner, card, OCT_10, 10_000, 1);
        jdbc.update("UPDATE payment_transaction SET recommendation_result_json = ?, recommended_rank = 1,"
                + " user_selected = FALSE WHERE payment_transaction_id = ?", result.formatted(otherCard, card), reRanked);
        // 추천 결과로는 1순위지만 승인 때 순위가 2 → 세지 않는다
        long demoted = approved(owner, card, OCT_10.plusHours(1), 10_000, 1);
        jdbc.update("UPDATE payment_transaction SET recommendation_result_json = ?, recommended_rank = 2,"
                + " user_selected = TRUE WHERE payment_transaction_id = ?", result.formatted(card, otherCard), demoted);
        // 추천 밖 카드를 직접 고름(순위 없음) → 추천 결과가 1순위여도 세지 않는다
        long outside = approved(owner, card, OCT_10.plusHours(2), 10_000, 1);
        jdbc.update("UPDATE payment_transaction SET recommendation_result_json = ?, recommended_rank = NULL,"
                + " user_selected = TRUE WHERE payment_transaction_id = ?", result.formatted(card, otherCard), outside);

        mockMvc.perform(list().param("yearMonth", OCTOBER))
                .andExpect(jsonPath("$.data.summary.yamuPickCount").value(1));
    }

    @Test
    void 새_필터의_잘못된_값은_400_INVALID_REQUEST다() throws Exception {
        expectInvalid(list().param("q", "가".repeat(51)), "q");
        expectInvalid(list().param("categoryCode", "F01;DROP"), "categoryCode");
        expectInvalid(list().param("spendingClass", "FOOD"), "spendingClass");
        expectInvalid(list().param("channel", "UNKNOWN"), "channel");
        mockMvc.perform(list().param("benefitStatus", "MAYBE"))
                .andExpect(status().isBadRequest());
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
        return benefit(paymentId, krw, "AT_BILLING");
    }

    private long benefit(long paymentId, long krw, String timing) {
        long cardBenefit = insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "CAFE-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", "카페 10% 청구할인",
                "benefit_type", "DISCOUNT", "calculation_type", "FIXED", "fixed_value", krw, "value_unit", "KRW",
                "application_timing", timing, "created_at", now()));
        return insert("payment_benefit", "payment_benefit_id", Map.of("payment_transaction_id", paymentId,
                "card_product_id", product, "card_benefit_id", cardBenefit, "benefit_value", krw, "value_unit", "KRW",
                "krw_amount", krw, "created_at", now()));
    }

    /**
     * 종류·반영 시점을 정한 혜택 한 줄.
     *
     * @param timing 약관 미확인이면 null
     * @param krw    원화로 바꾸지 못했으면 null
     */
    private long typedBenefit(long paymentId, String type, String timing, long value, Long krw) {
        Map<String, Object> cb = new HashMap<>(Map.of("card_product_id", product,
                "benefit_code", "B-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", type + " 혜택",
                "benefit_type", type, "calculation_type", "FIXED", "fixed_value", value,
                "value_unit", krw == null ? "POINT" : "KRW", "created_at", now()));
        cb.put("application_timing", timing);
        long cardBenefit = insert("card_benefit", "card_benefit_id", cb);
        Map<String, Object> pb = new HashMap<>(Map.of("payment_transaction_id", paymentId, "card_product_id", product,
                "card_benefit_id", cardBenefit, "benefit_value", value, "value_unit", krw == null ? "POINT" : "KRW",
                "created_at", now()));
        pb.put("krw_amount", krw);
        return insert("payment_benefit", "payment_benefit_id", pb);
    }

    /** 적립 혜택 줄. {@code krw}가 null이면 원화로 바꾸지 못한 줄이다. */
    private long pointBenefit(long paymentId, String points, Long krw) {
        long cardBenefit = insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "POINT-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", "카페 5% 적립",
                "benefit_type", "POINT", "calculation_type", "RATE", "rate", new java.math.BigDecimal("0.05"),
                "value_unit", "POINT", "application_timing", "POINT_ACCRUAL", "created_at", now()));
        Map<String, Object> row = new HashMap<>(Map.of("payment_transaction_id", paymentId, "card_product_id", product,
                "card_benefit_id", cardBenefit, "benefit_value", new java.math.BigDecimal(points), "value_unit", "POINT",
                "created_at", now()));
        row.put("krw_amount", krw);
        return insert("payment_benefit", "payment_benefit_id", row);
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
