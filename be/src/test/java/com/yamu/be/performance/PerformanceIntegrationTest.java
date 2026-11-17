package com.yamu.be.performance;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드별 실적 현황·상세를 인증 필터·실제 MySQL까지 묶어 확인한다(S15P31E103-373). 카드·혜택·한도·실적·결제 행은 SQL로 바로 넣는다.
 * 서버 시계는 실제 시계라, 이번 달 추세 판정(날짜에 따라 달라짐)은 {@link PerformanceProgressTest}에서 본다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-performance-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceIntegrationTest extends MySqlTestSupport {

    static final String LIST = "/api/users/me/card-performances";
    static final YearMonth THIS_MONTH = YearMonth.now(ZoneId.of("Asia/Seoul"));
    static final YearMonth LAST_MONTH = THIS_MONTH.minusMonths(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    /**
     * 기준 30만(생활 할인)·50만(카페 할인) 상품. 두 혜택이 통합 원화 월 한도(30만~50만 1만원·50만~ 2만원)를 함께 쓰고,
     * 카페 할인에는 그 안에 따로 월 5천원 한도와 포인트 한도가 더 있다(실제 시드 노리2 체크카드와 같은 모양).
     */
    long tiered;
    /** 혜택에 실적 조건이 없는 상품. */
    long plain;
    long merchant;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        tiered = fixtures.product();
        plain = fixtures.product();
        long life = benefit(tiered, "생활 10% 할인", 300_000);
        long cafe = benefit(tiered, "카페 20% 할인", 500_000);
        long group = limitGroup(tiered, "LIFE_SHARED", "MONTHLY", "KRW");
        link(tiered, life, group);
        tier(group, 300_000, 500_000L, 10_000);
        tier(group, 500_000, null, 20_000);
        link(tiered, cafe, group);
        // 통합 한도 안의 카페 전용 한도(기본 한도 5천원)는 통합 한도와 겹치므로 따로 더하지 않는다
        long cafeOnly = limitGroup(tiered, "CAFE_MONTHLY", "MONTHLY", "KRW");
        jdbc.update("UPDATE limit_group SET base_limit_value = 5000 WHERE limit_group_id = ?", cafeOnly);
        link(tiered, cafe, cafeOnly);
        // 포인트 한도는 원화 최대치에 넣지 않는다
        long points = limitGroup(tiered, "CAFE_POINT", "MONTHLY", "POINT");
        link(tiered, cafe, points);
        benefit(plain, "모든 가맹점 1% 할인", 0);
        merchant = insert("merchant", "merchant_id", timestamps(Map.of("merchant_name", "스타벅스",
                "brand_code", "BRAND-" + UUID.randomUUID(), "merchant_category_code", "COFFEE_SHOP")));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(LIST)).andExpect(status().isUnauthorized());
    }

    @Test
    void 목록은_내_등록된_카드만_카드_목록_순서로_보인다() throws Exception {
        long active = card(owner, tiered, "ACTIVE", "생활카드");
        long inactive = card(owner, plain, "INACTIVE", null);
        card(owner, tiered, "PENDING", null);
        card(owner, tiered, "UNREGISTERED", null);
        card(other, tiered, "ACTIVE", null);

        mockMvc.perform(get(LIST).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value(THIS_MONTH.toString()))
                .andExpect(jsonPath("$.data.daysLeft").value(THIS_MONTH.lengthOfMonth()
                        - LocalDate.now(ZoneId.of("Asia/Seoul")).getDayOfMonth()))
                .andExpect(jsonPath("$.data.cards[*].userCardId").value(contains((int) active, (int) inactive)))
                .andExpect(jsonPath("$.data.cards[0].nickname").value("생활카드"))
                .andExpect(jsonPath("$.data.cards[0].issuer.code").value("TEST"))
                .andExpect(jsonPath("$.data.cards[0].issuerName").value("TEST"))
                .andExpect(jsonPath("$.data.cards[0].cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.cards[0].guide").value("INSUFFICIENT_DATA"))   // 실적 행 없음
                .andExpect(jsonPath("$.data.cards[1].guide").value(nullValue()))           // 실적 조건 없음
                .andExpect(jsonPath("$.data.dataSyncedAt").value(nullValue()));
    }

    @Test
    void 실적_조건이_없는_카드는_NO_TARGET이고_체크카드도_같다() throws Exception {
        jdbc.update("UPDATE card_product SET card_type = 'CHECK' WHERE card_product_id = ?", plain);
        long check = card(owner, plain, "ACTIVE", null);
        performance(check, THIS_MONTH, 80_000);

        mockMvc.perform(get(LIST).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.cards[0].status").value("NO_TARGET"))
                .andExpect(jsonPath("$.data.cards[0].targetAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.cards[0].targetLabel").value(nullValue()))
                .andExpect(jsonPath("$.data.cards[0].thresholds", hasSize(0)))
                .andExpect(jsonPath("$.data.cards[0].recognizedAmount").value(80_000))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(0));
    }

    @Test
    void 실적_행이_없으면_INSUFFICIENT_DATA다() throws Exception {
        card(owner, tiered, "ACTIVE", null);

        mockMvc.perform(get(LIST).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.cards[0].targetAmount").value(300_000))
                .andExpect(jsonPath("$.data.cards[0].targetLabel").value("30만원 이상"))
                .andExpect(jsonPath("$.data.cards[0].thresholds", contains(300_000, 500_000)))   // 여러 단계 카드의 단계 기준(작은 순)
                .andExpect(jsonPath("$.data.cards[0].recognizedAmount").value(0))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(300_000));
    }

    @Test
    void 지난_달은_기준을_모두_넘으면_ACHIEVED_못_채우면_AT_RISK다() throws Exception {
        long done = card(owner, tiered, "ACTIVE", null);
        long missed = card(owner, tiered, "ACTIVE", null);
        performance(done, LAST_MONTH, 520_000);
        performance(missed, LAST_MONTH, 320_000);

        mockMvc.perform(get(LIST).param("yearMonth", LAST_MONTH.toString()).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.daysLeft").value(0))
                .andExpect(jsonPath("$.data.cards[0].status").value("ACHIEVED"))
                .andExpect(jsonPath("$.data.cards[0].targetAmount").value(500_000))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(0))
                .andExpect(jsonPath("$.data.cards[1].status").value("AT_RISK"))
                .andExpect(jsonPath("$.data.cards[1].targetAmount").value(500_000))
                .andExpect(jsonPath("$.data.cards[1].remainingAmount").value(180_000));
    }

    @Test
    void 인정액_미확인_결제가_있으면_isProvisional이고_최근_반영은_인정된_승인_결제다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        long quiet = card(owner, tiered, "ACTIVE", null);
        performance(card, LAST_MONTH, 30_000);
        performance(quiet, LAST_MONTH, 0);
        LocalDateTime base = LAST_MONTH.atDay(10).atTime(3, 0);
        payment(card, "APPROVED", base, 10_000L, LAST_MONTH);
        payment(card, "APPROVED", base.plusHours(1), 20_000L, LAST_MONTH);
        payment(card, "APPROVED", base.plusHours(2), null, LAST_MONTH);       // 미확인 → isProvisional
        payment(card, "APPROVED", base.plusHours(3), 0L, LAST_MONTH);         // 실적 제외(0원) → 최근 반영 아님
        payment(card, "CANCELED", base.plusHours(4), 50_000L, LAST_MONTH);    // 취소 → 최근 반영 아님
        payment(card, "APPROVED", base.plusHours(5), 70_000L, THIS_MONTH);    // 다른 실적 월

        mockMvc.perform(get(LIST).param("yearMonth", LAST_MONTH.toString()).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].isProvisional").value(true))
                .andExpect(jsonPath("$.data.cards[0].recentChange.amount").value(20_000))
                .andExpect(jsonPath("$.data.cards[0].recentChange.merchantName").value("스타벅스"))
                .andExpect(jsonPath("$.data.cards[0].recentChange.approvedAt").exists())
                .andExpect(jsonPath("$.data.cards[1].isProvisional").value(false))
                .andExpect(jsonPath("$.data.cards[1].recentChange").value(nullValue()));
    }

    @Test
    void 상세는_구간별_새_혜택과_원화_월_한도_합과_달성_여부를_준다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        performance(card, THIS_MONTH, 350_000);

        mockMvc.perform(get("/api/user-cards/{id}/performance", card).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userCardId").value(card))
                .andExpect(jsonPath("$.data.yearMonth").value(THIS_MONTH.toString()))
                .andExpect(jsonPath("$.data.summary.targetAmount").value(500_000))
                .andExpect(jsonPath("$.data.summary.recognizedAmount").value(350_000))
                .andExpect(jsonPath("$.data.summary.remainingAmount").value(150_000))
                .andExpect(jsonPath("$.data.summary.status").value(in(List.of("ON_TRACK", "AT_RISK"))))
                .andExpect(jsonPath("$.data.tiers", hasSize(2)))
                .andExpect(jsonPath("$.data.tiers[0].threshold").value(300_000))
                .andExpect(jsonPath("$.data.tiers[0].label").value("30만원 이상"))
                .andExpect(jsonPath("$.data.tiers[0].benefitSummary").value("생활 10% 할인"))
                .andExpect(jsonPath("$.data.tiers[0].benefitAmount").value(10_000))
                .andExpect(jsonPath("$.data.tiers[0].achieved").value(true))
                .andExpect(jsonPath("$.data.tiers[1].threshold").value(500_000))
                .andExpect(jsonPath("$.data.tiers[1].benefitSummary").value("카페 20% 할인"))
                .andExpect(jsonPath("$.data.tiers[1].benefitAmount").value(20_000))
                .andExpect(jsonPath("$.data.tiers[1].achieved").value(false))
                .andExpect(jsonPath("$.data.nextMonthImpact.message")
                        .value("150,000원 더 쓰면 다음 달 50만원 이상 구간 혜택을 받아요"));
    }

    @Test
    void 원화_월_한도가_없는_혜택이_열리는_구간은_금액을_말하지_않는다() throws Exception {
        long product = fixtures.product();
        long capped = benefit(product, "편의점 5% 할인", 300_000);
        benefit(product, "해외 1% 적립", 500_000);   // 한도 없음 → 50만 구간부터는 원화 최대치가 없다
        long group = limitGroup(product, "CVS_MONTHLY", "MONTHLY", "KRW");
        jdbc.update("UPDATE limit_group SET base_limit_value = 3000 WHERE limit_group_id = ?", group);
        link(product, capped, group);
        long card = card(owner, product, "ACTIVE", null);
        performance(card, THIS_MONTH, 0);

        mockMvc.perform(get("/api/user-cards/{id}/performance", card).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.tiers[0].benefitAmount").value(3_000))
                .andExpect(jsonPath("$.data.tiers[1].benefitSummary").value("해외 1% 적립"))
                .andExpect(jsonPath("$.data.tiers[1].benefitAmount").value(nullValue()));
    }

    @Test
    void 실적_조건이_없는_카드의_상세는_구간이_비고_다음_달_문구가_없다() throws Exception {
        long card = card(owner, plain, "INACTIVE", null);
        performance(card, THIS_MONTH, 10_000);

        mockMvc.perform(get("/api/user-cards/{id}/performance", card).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.summary.status").value("NO_TARGET"))
                .andExpect(jsonPath("$.data.tiers", hasSize(0)))
                .andExpect(jsonPath("$.data.nextMonthImpact").value(nullValue()))
                // 구간이 없어도 받는 혜택은 보여 준다(S15P31E103-615). 0.1 → "10%"(1E+1로 쓰지 않는다)
                .andExpect(jsonPath("$.data.benefits", hasSize(1)))
                .andExpect(jsonPath("$.data.benefits[0].name").value("모든 가맹점 1% 할인"))
                .andExpect(jsonPath("$.data.benefits[0].valueLabel").value("10%"))
                .andExpect(jsonPath("$.data.benefits[0].minPerformanceAmount").value(0))
                .andExpect(jsonPath("$.data.benefits[0].limited").value(false));
    }

    @Test
    void 상세는_혜택마다_값과_필요_실적과_건당_최대와_월_횟수를_준다() throws Exception {
        long product = fixtures.product();
        long base = benefitRow(product, "기본 할인", "RATE", "0.008", 0, 0);
        long coffee = benefitRow(product, "커피 할인", "RATE", "0.2", 200_000, 10_000);
        long phone = benefitRow(product, "통신 자동이체", "FIXED", "2500", 300_000, 50_000);
        // 건당 원화 한도는 그 혜택에 필요한 실적(20만)에서의 구간 값
        long perTxn = limitGroup(product, "COFFEE_TXN", "PER_TRANSACTION", "KRW");
        tier(perTxn, 200_000, 500_000L, 4_000);
        tier(perTxn, 500_000, null, 6_000);
        link(product, coffee, perTxn);
        long count = limitGroup(product, "PHONE_COUNT", "MONTHLY", "COUNT");
        tier(count, 300_000, null, 1);
        link(product, phone, count);
        // 버전을 몰라 화면에서 빼는 한도(같은 그룹 코드 두 버전)에만 걸려도 한도 없음이라 하지 않는다
        long unknownA = limitGroup(product, "BASE_MONTH", "MONTHLY", "KRW");
        long unknownB = insert("limit_group", "limit_group_id", Map.of("card_product_id", product,
                "group_code", "BASE_MONTH", "policy_version", 2, "group_name", "BASE_MONTH", "period_type", "MONTHLY",
                "limit_unit", "KRW", "created_at", now()));
        link(product, base, unknownA);
        link(product, base, unknownB);
        long card = card(owner, product, "ACTIVE", null);
        performance(card, THIS_MONTH, 250_000);

        mockMvc.perform(get("/api/user-cards/{id}/performance", card).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.benefits[*].name").value(contains("기본 할인", "커피 할인", "통신 자동이체")))
                .andExpect(jsonPath("$.data.benefits[0].valueLabel").value("0.8%"))
                .andExpect(jsonPath("$.data.benefits[0].minPerformanceAmount").value(0))
                .andExpect(jsonPath("$.data.benefits[0].perTransactionLimit").value(nullValue()))
                .andExpect(jsonPath("$.data.benefits[0].limited").value(true))
                .andExpect(jsonPath("$.data.benefits[1].valueLabel").value("20%"))
                .andExpect(jsonPath("$.data.benefits[1].minPerformanceAmount").value(200_000))
                .andExpect(jsonPath("$.data.benefits[1].minPaymentAmount").value(10_000))
                .andExpect(jsonPath("$.data.benefits[1].perTransactionLimit").value(4_000))
                .andExpect(jsonPath("$.data.benefits[1].monthlyCount").value(nullValue()))
                .andExpect(jsonPath("$.data.benefits[1].limited").value(true))
                .andExpect(jsonPath("$.data.benefits[2].valueLabel").value("2,500원"))
                .andExpect(jsonPath("$.data.benefits[2].minPerformanceAmount").value(300_000))
                .andExpect(jsonPath("$.data.benefits[2].minPaymentAmount").value(50_000))
                .andExpect(jsonPath("$.data.benefits[2].perTransactionLimit").value(nullValue()))
                .andExpect(jsonPath("$.data.benefits[2].monthlyCount").value(1));
    }

    @Test
    void 남의_카드_등록_전_등록_해제_없는_카드의_상세는_404다() throws Exception {
        long others = card(other, tiered, "ACTIVE", null);
        long pending = card(owner, tiered, "PENDING", null);
        long unregistered = card(owner, tiered, "UNREGISTERED", null);

        for (long id : new long[] {others, pending, unregistered, 999_999_999L}) {
            mockMvc.perform(get("/api/user-cards/{id}/performance", id).with(AuthUsers.asUser(owner)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void yearMonth는_형식이_맞고_2000_01부터_2100_12까지만_받는다() throws Exception {
        for (String bad : new String[] {"2026-13", "202611", "0000-01", "1999-12", "2101-01"}) {
            mockMvc.perform(get(LIST).param("yearMonth", bad).with(AuthUsers.asUser(owner)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get(LIST).param("yearMonth", "1999-12").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("yearMonth"));
        mockMvc.perform(get(LIST).param("yearMonth", "2000-01").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk());
    }

    // ── 픽스처 ──

    private long card(long user, long product, String status, String alias) {
        long id = fixtures.card(user, product, null, status);
        if (alias != null) {
            jdbc.update("UPDATE user_card SET card_alias = ? WHERE user_card_id = ?", alias, id);
        }
        if (!status.equals("PENDING")) {
            jdbc.update("UPDATE user_card SET registered_at = ? WHERE user_card_id = ?", now().plusNanos(id * 1000), id);
        }
        return id;
    }

    private void performance(long card, YearMonth month, long amount) {
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, card, Date.valueOf(month.atDay(1)), amount, now(), now());
    }

    private long benefit(long product, String name, long minPerformance) {
        return insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "B-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", name,
                "benefit_type", "DISCOUNT", "calculation_type", "RATE", "rate", new BigDecimal("0.1"),
                "value_unit", "KRW", "min_performance_amount", minPerformance, "created_at", now()));
    }

    /** 계산 방식·값·최소 결제 금액을 고를 수 있는 혜택. FIXED면 원화 정액. */
    private long benefitRow(long product, String name, String calculation, String value, long minPerformance,
            long minPayment) {
        Map<String, Object> row = new HashMap<>(Map.of("card_product_id", product,
                "benefit_code", "B-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", name,
                "benefit_type", "DISCOUNT", "calculation_type", calculation, "value_unit", "KRW",
                "min_performance_amount", minPerformance, "min_payment_amount", minPayment, "created_at", now()));
        row.put(calculation.equals("RATE") ? "rate" : "fixed_value", new BigDecimal(value));
        return insert("card_benefit", "card_benefit_id", row);
    }

    private long limitGroup(long product, String code, String period, String unit) {
        return insert("limit_group", "limit_group_id", Map.of("card_product_id", product, "group_code", code,
                "policy_version", 1, "group_name", code, "period_type", period, "limit_unit", unit,
                "created_at", now()));
    }

    private void link(long product, long benefit, long group) {
        insert("benefit_limit_link", "benefit_limit_link_id", Map.of("card_product_id", product,
                "card_benefit_id", benefit, "limit_group_id", group, "created_at", now()));
    }

    private void tier(long group, long min, Long max, long limit) {
        Map<String, Object> values = new HashMap<>(Map.of("limit_group_id", group, "min_performance_amount", min,
                "limit_value", limit, "created_at", now()));
        if (max != null) {
            values.put("max_performance_amount", max);
        }
        insert("tier_limit", "tier_limit_id", values);
    }

    /** 승인·취소 결제. 승인 칸(DB CHECK ck_payment_approval)을 채운다. 인정액 null은 미확인. */
    private void payment(long card, String status, LocalDateTime approvedAt, Long recognized, YearMonth month) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                card);
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", owner, "merchant_id", merchant,
                "payment_channel", "OFFLINE", "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", randomHash(), "source", "YAMU", "status", status,
                "requested_amount", 30_000, "installment_months", 1, "requested_at", approvedAt.minusSeconds(2))));
        row.put("user_card_id", card);
        row.put("card_product_id", product);
        row.put("approved_at", approvedAt);
        row.put("approved_amount", 30_000);
        row.put("performance_month", Date.valueOf(month.atDay(1)));
        if (recognized != null) {
            row.put("recognized_performance_amount", recognized);
        }
        if (status.equals("CANCELED")) {
            row.put("canceled_at", approvedAt.plusMinutes(1));
        }
        insert("payment_transaction", "payment_transaction_id", row);
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
