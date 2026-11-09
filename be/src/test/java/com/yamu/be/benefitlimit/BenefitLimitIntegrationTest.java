package com.yamu.be.benefitlimit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.sql.Date;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 혜택 한도 현황을 인증 필터·실제 MySQL까지 묶어 확인한다(S15P31E103-374). 카드·혜택·한도·실적·사용량 행은 SQL로 바로 넣는다.
 * 판정 경계(20%·구간 하한·0원 구간)는 {@link LimitJudgeTest}에서 본다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-benefit-limit-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BenefitLimitIntegrationTest extends MySqlTestSupport {

    static final String URL = "/api/benefit-limits";
    static final YearMonth THIS_MONTH = YearMonth.now(ZoneId.of("Asia/Seoul"));
    static final YearMonth LAST_MONTH = THIS_MONTH.minusMonths(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    /**
     * 생활 할인·카페 할인(둘 다 전월 30만원 이상)이 같이 쓰는 원화 월 공유 한도(30만~50만 1만원, 50만~ 2만원),
     * 카페 할인의 월 3회 횟수 한도, 생활 할인의 건별 한도(목록에서 빠짐).
     */
    long tiered;
    long shared;
    long count;
    /** 한도가 없는 혜택만 있는 상품. */
    long plain;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        tiered = fixtures.product();
        plain = fixtures.product();
        long life = benefit(tiered, "생활 10% 할인", 300_000);
        long cafe = benefit(tiered, "카페 20% 할인", 300_000);
        shared = limitGroup(tiered, "LIFE_SHARED", "생활·카페 공유 월 한도", "MONTHLY", "KRW", null);
        link(tiered, life, shared);
        link(tiered, cafe, shared);
        tier(shared, 300_000, 500_000L, 10_000);
        tier(shared, 500_000, null, 20_000);
        count = limitGroup(tiered, "CAFE_COUNT", "카페 월 횟수 한도", "MONTHLY", "COUNT", 3L);
        link(tiered, cafe, count);
        long perTransaction = limitGroup(tiered, "LIFE_TXN", "생활 건별 한도", "PER_TRANSACTION", "KRW", 5_000L);
        link(tiered, life, perTransaction);
        benefit(plain, "모든 가맹점 1% 할인", 0);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void 내_등록된_카드만_카드_목록_순서로_보이고_남의_카드는_없다() throws Exception {
        long active = card(owner, tiered, "ACTIVE", "생활카드");
        long inactive = card(owner, plain, "INACTIVE", null);
        card(owner, tiered, "PENDING", null);
        card(owner, tiered, "UNREGISTERED", null);
        long others = card(other, tiered, "ACTIVE", null);
        performance(others, LAST_MONTH, 600_000);
        usage(others, shared, THIS_MONTH, 1_000);

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value(THIS_MONTH.toString()))
                .andExpect(jsonPath("$.data.cards[*].userCardId").value(contains((int) active, (int) inactive)))
                .andExpect(jsonPath("$.data.cards[0].nickname").value("생활카드"))
                .andExpect(jsonPath("$.data.cards[0].issuer.code").value("TEST"))
                .andExpect(jsonPath("$.data.dataSyncedAt").value(nullValue()))
                // 내 카드는 지난달 실적 행이 없어 0원 → 잠김. 남의 카드 실적·사용량은 합계에 들어가지 않는다
                .andExpect(jsonPath("$.data.summary.receivedAmount").value(0))
                .andExpect(jsonPath("$.data.summary.remainingAmount").value(0));
    }

    @Test
    void 공유_한도는_한_줄이고_사용량을_뺀_남은_한도와_합계를_준다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        performance(card, LAST_MONTH, 350_000);
        usage(card, shared, THIS_MONTH, 8_500);
        usage(card, shared, LAST_MONTH, 9_000);    // 지난달 사용량은 안 본다
        usage(card, count, THIS_MONTH, 1);

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits", hasSize(2)))
                .andExpect(jsonPath("$.data.cards[0].limits[0].name").value("생활·카페 공유 월 한도"))
                .andExpect(jsonPath("$.data.cards[0].limits[0].remainingAmount").value(1_500))
                .andExpect(jsonPath("$.data.cards[0].limits[0].status").value("NEAR_END"))
                .andExpect(jsonPath("$.data.cards[0].limits[0].lockedReason").value(nullValue()))
                // 횟수 한도는 상태만, 금액은 null이고 합계에 안 들어간다
                .andExpect(jsonPath("$.data.cards[0].limits[1].name").value("카페 월 횟수 한도"))
                .andExpect(jsonPath("$.data.cards[0].limits[1].remainingAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.cards[0].limits[1].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(1_500))
                .andExpect(jsonPath("$.data.summary.receivedAmount").value(8_500))
                .andExpect(jsonPath("$.data.summary.remainingAmount").value(1_500));
    }

    @Test
    void 사용량_행이_없으면_0으로_보고_지난달_실적_구간의_한도를_다_남긴다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        performance(card, LAST_MONTH, 520_000);

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits[0].remainingAmount").value(20_000))
                .andExpect(jsonPath("$.data.cards[0].limits[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.cards[0].limits[1].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.summary.remainingAmount").value(20_000));
    }

    @Test
    void 다_쓴_한도는_EXHAUSTED다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        performance(card, LAST_MONTH, 350_000);
        usage(card, shared, THIS_MONTH, 10_000);
        usage(card, count, THIS_MONTH, 3);

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits[0].remainingAmount").value(0))
                .andExpect(jsonPath("$.data.cards[0].limits[0].status").value("EXHAUSTED"))
                .andExpect(jsonPath("$.data.cards[0].limits[1].status").value("EXHAUSTED"))
                .andExpect(jsonPath("$.data.summary.receivedAmount").value(10_000));
    }

    @Test
    void 지난달_실적이_모자라면_LOCKED이고_사유에_기준_실적이_나온다() throws Exception {
        long short1 = card(owner, tiered, "ACTIVE", null);
        long noRow = card(owner, tiered, "ACTIVE", null);
        performance(short1, LAST_MONTH, 299_999);
        performance(short1, THIS_MONTH, 900_000);   // 이번 달 실적은 다음 달 한도라 안 본다

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits[0].status").value("LOCKED"))
                .andExpect(jsonPath("$.data.cards[0].limits[0].remainingAmount").value(0))
                .andExpect(jsonPath("$.data.cards[0].limits[0].lockedReason").value("지난달 실적이 30만원에 못 미쳐요"))
                .andExpect(jsonPath("$.data.cards[0].limits[1].status").value("LOCKED"))
                .andExpect(jsonPath("$.data.cards[0].limits[1].remainingAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(0))
                // 지난달 실적 행이 없으면 0원으로 본다
                .andExpect(jsonPath("$.data.cards[1].userCardId").value(noRow))
                .andExpect(jsonPath("$.data.cards[1].limits[0].status").value("LOCKED"));
    }

    @Test
    void 월_한도가_없는_카드는_빈_목록이고_남은_한도는_null이다() throws Exception {
        card(owner, plain, "ACTIVE", null);

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits", hasSize(0)))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(nullValue()))
                .andExpect(jsonPath("$.data.summary.receivedAmount").value(0))
                .andExpect(jsonPath("$.data.summary.remainingAmount").value(0));
    }

    @Test
    void 카드가_없으면_빈_목록이다() throws Exception {
        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards", hasSize(0)))
                .andExpect(jsonPath("$.data.summary.remainingAmount").value(0));
    }

    @Test
    void 오늘_유효한_버전이_둘인_한도_그룹은_현행을_몰라_뺀다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        performance(card, LAST_MONTH, 350_000);
        jdbc.update("""
                INSERT INTO limit_group (card_product_id, group_code, policy_version, group_name, period_type, limit_unit,
                                         base_limit_value, created_at)
                VALUES (?, 'LIFE_SHARED', 2, '새 공유 한도', 'MONTHLY', 'KRW', 30000, ?)
                """, tiered, now());

        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits", hasSize(1)))
                .andExpect(jsonPath("$.data.cards[0].limits[0].name").value("카페 월 횟수 한도"))
                .andExpect(jsonPath("$.data.cards[0].remainingAmount").value(nullValue()));
    }

    @Test
    void 끝난_혜택에만_연결된_한도는_뺀다() throws Exception {
        long card = card(owner, tiered, "ACTIVE", null);
        performance(card, LAST_MONTH, 350_000);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("UPDATE card_benefit SET valid_from = ?, valid_until = ? WHERE card_product_id = ? AND benefit_name = ?",
                Date.valueOf(today.minusYears(1)), Date.valueOf(today), tiered, "카페 20% 할인");

        // 카페 할인이 끝나 횟수 한도는 빠지고, 공유 한도는 생활 할인으로 남는다
        mockMvc.perform(get(URL).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.cards[0].limits", hasSize(1)))
                .andExpect(jsonPath("$.data.cards[0].limits[0].name").value("생활·카페 공유 월 한도"));
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

    private void usage(long card, long group, YearMonth month, long used) {
        jdbc.update("""
                INSERT INTO monthly_limit_usage (user_card_id, card_product_id, limit_group_id, usage_month, used_value,
                                                 created_at, updated_at)
                SELECT user_card_id, card_product_id, ?, ?, ?, ?, ? FROM user_card WHERE user_card_id = ?
                """, group, Date.valueOf(month.atDay(1)), used, now(), now(), card);
    }

    private long benefit(long product, String name, long minPerformance) {
        return insert("card_benefit", "card_benefit_id", Map.of("card_product_id", product,
                "benefit_code", "B-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", name,
                "benefit_type", "DISCOUNT", "calculation_type", "RATE", "rate", new BigDecimal("0.1"),
                "value_unit", "KRW", "min_performance_amount", minPerformance, "created_at", now()));
    }

    private long limitGroup(long product, String code, String name, String period, String unit, Long base) {
        Map<String, Object> row = new HashMap<>(Map.of("card_product_id", product, "group_code", code,
                "policy_version", 1, "group_name", name, "period_type", period, "limit_unit", unit,
                "created_at", now()));
        if (base != null) {
            row.put("base_limit_value", base);
        }
        return insert("limit_group", "limit_group_id", row);
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

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
