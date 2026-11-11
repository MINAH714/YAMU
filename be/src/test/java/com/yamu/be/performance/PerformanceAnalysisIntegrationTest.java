package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 유불리 분석·판단 기록을 인증 필터·실제 MySQL까지 묶어 확인한다(S15P31E103-375).
 *
 * <ul>
 *   <li>서버 시계는 2026-11-10 12:00(한국)으로 고정한다. 자연 소비는 지난 3개월의 11일~말일을 센다.</li>
 *   <li>목표 실적은 카드별 실적 현황(-373)과 같은 규칙으로 실제 혜택·한도 행에서 읽는다: 테스트 상품은 30만원 구간
 *       (생활 10% 할인, 원화 월 한도 1만원), 혜택 행이 없는 상품은 목표 없음.</li>
 *   <li>동시 기록은 두 트랜잭션이 각자 커밋해야 해서 테스트 트랜잭션을 쓰지 않는다. 테스트마다 사용자를 새로 만든다.</li>
 * </ul>
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-performance-analysis-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceAnalysisIntegrationTest extends MySqlTestSupport {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    long account;
    long product;
    long card;
    long merchant;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        account = fixtures.account(owner);
        product = fixtures.product();
        card = card(owner, product, "ACTIVE");
        merchant = insert("merchant", "merchant_id", timestamps(Map.of("merchant_name", "테스트 가맹점",
                "brand_code", "BRAND-" + UUID.randomUUID(), "merchant_category_code", "CONVENIENCE_STORE")));
        long life = benefit(product, "생활 10% 할인", 300_000);
        long monthly = limitGroup(product, "LIFE_MONTHLY", 10_000L);
        link(product, life, monthly);
    }

    // ── 분석 ──

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(analysisUrl(card))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(decisionUrl(card)).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"SKIP\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 남의_카드와_등록_전_해제한_카드는_분석도_기록도_404다() throws Exception {
        long others = card(other, product, "ACTIVE");
        long pending = fixtures.card(owner, product, null, "PENDING");
        long unregistered = card(owner, product, "UNREGISTERED");
        for (long id : new long[] {others, pending, unregistered, 999_999_999L}) {
            mockMvc.perform(get(analysisUrl(id)).with(AuthUsers.asUser(owner)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
            decide(owner, id, "{\"decision\":\"SKIP\"}").andExpect(status().isNotFound());
        }
        assertThat(decisionRows(others)).isZero();
    }

    @Test
    void 목표가_없거나_이번_달_실적_행이_없거나_지난_실적이_없으면_422다() throws Exception {
        long noTarget = card(owner, fixtures.product(), "ACTIVE");
        performance(noTarget, NOVEMBER, 100_000);
        expect422(noTarget);

        // 이번 달 실적 행 없음
        expect422(card);

        // 이번 달은 있지만 지난 3개월 행이 하나도 없음(7월 행은 범위 밖)
        performance(card, NOVEMBER, 100_000);
        performance(card, YearMonth.of(2026, 7), 300_000);
        expect422(card);
    }

    @Test
    void 이미_달성했으면_지난_실적이_없어도_ALREADY_ACHIEVED다() throws Exception {
        performance(card, NOVEMBER, 300_000);

        analysis(owner, card)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guide").value("ALREADY_ACHIEVED"))
                .andExpect(jsonPath("$.data.remainingAmount").value(0))
                .andExpect(jsonPath("$.data.steps", hasSize(0)))
                .andExpect(jsonPath("$.data.benefitsAtStake[0].name").value("생활 10% 할인"));
    }

    @Test
    void 자연_소비는_실적_행이_있는_달의_같은_기간_승인_결제만_센다() throws Exception {
        performance(card, NOVEMBER, 200_000);
        // 8월은 실적 행이 없어 모르는 달(결제가 있어도 안 셈)
        payment(owner, card, LocalDate.of(2026, 8, 20), "APPROVED", 900_000L);
        performance(card, YearMonth.of(2026, 9), 0);
        payment(owner, card, LocalDate.of(2026, 9, 11), "APPROVED", 70_000L);   // 같은 기간 첫날
        payment(owner, card, LocalDate.of(2026, 9, 10), "APPROVED", 500_000L);  // 오늘 날짜까지는 안 셈
        payment(owner, card, LocalDate.of(2026, 9, 25), "CANCELED", 500_000L);  // 취소
        payment(owner, card, LocalDate.of(2026, 9, 26), "APPROVED", null);      // 인정액 미확인
        performance(card, YearMonth.of(2026, 10), 0);
        payment(owner, card, LocalDate.of(2026, 10, 31), "APPROVED", 50_000L);
        long othersCard = card(other, product, "ACTIVE");
        payment(other, othersCard, LocalDate.of(2026, 10, 20), "APPROVED", 900_000L);

        analysis(owner, card)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value("2026-11"))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-10T03:00:00Z"))
                .andExpect(jsonPath("$.data.daysLeft").value(20))
                .andExpect(jsonPath("$.data.projection.historyMonths").value(2))
                .andExpect(jsonPath("$.data.projection.naturalRecognizable").value(60_000))
                .andExpect(jsonPath("$.data.projection.averageDailyRecognizable").value(3_000))
                .andExpect(jsonPath("$.data.projection.projectedRecognizedAmount").value(260_000))
                .andExpect(jsonPath("$.data.projection.method").value("SCHEDULED_PLUS_AVG_3M"))
                .andExpect(jsonPath("$.data.isEstimate").value(true))
                .andExpect(jsonPath("$.data.steps[1].note")
                        .value("최근 2개월 같은 기간(11일~말일) 평균 · 9월 70,000원 · 10월 50,000원"));
    }

    @Test
    void 평소대로_쓰면_채우는_카드는_KEEP_NATURAL이고_이_카드로_낼_예정_지출을_더한다() throws Exception {
        history(card, 80_000);
        performance(card, NOVEMBER, 200_000);
        cashflow(owner, "구독", 25_000, TODAY.plusDays(5), null, card, null);
        cashflow(owner, "다음 달", 99_000, LocalDate.of(2026, 12, 1), null, card, null);

        analysis(owner, card)
                .andExpect(jsonPath("$.data.guide").value("KEEP_NATURAL"))
                .andExpect(jsonPath("$.data.projection.scheduledRecognizable").value(25_000))
                .andExpect(jsonPath("$.data.projection.projectedRecognizedAmount").value(305_000))
                .andExpect(jsonPath("$.data.projection.likelihood").value("LIKELY"))
                .andExpect(jsonPath("$.data.shortfall").value(0))
                .andExpect(jsonPath("$.data.decision").value(nullValue()))
                .andExpect(jsonPath("$.data.scenario").value(nullValue()));
    }

    @Test
    void 예정_지출을_옮기면_채우는_카드는_달성_추천이고_what_if는_저장하지_않는다() throws Exception {
        history(card, 60_000);
        performance(card, NOVEMBER, 200_000);
        long telecom = cashflow(owner, "통신비", 50_000, LocalDate.of(2026, 11, 25), account, null, "TELECOMMUNICATION");
        long tooSoon = cashflow(owner, "인터넷", 30_000, LocalDate.of(2026, 11, 11), account, null, null);
        long rent = cashflow(owner, "월세", 500_000, LocalDate.of(2026, 11, 25), account, null, null);

        analysis(owner, card)
                .andExpect(jsonPath("$.data.guide").value("ACHIEVE_RECOMMENDED"))
                .andExpect(jsonPath("$.data.headline").value("통신비 결제 카드만 바꾸면 추가 지출 없이 채워요"))
                .andExpect(jsonPath("$.data.shortfall").value(0))
                .andExpect(jsonPath("$.data.extraBenefitIfMet").value(10_000))
                .andExpect(jsonPath("$.data.movableItems", hasSize(3)))
                .andExpect(jsonPath("$.data.movableItems[0].cashflowItemId").value(rent))
                .andExpect(jsonPath("$.data.movableItems[0].eligible").value(false))
                .andExpect(jsonPath("$.data.movableItems[0].reason").value("카드로 낼 수 없는 지출이에요"))
                .andExpect(jsonPath("$.data.movableItems[1].cashflowItemId").value(telecom))
                .andExpect(jsonPath("$.data.movableItems[1].eligible").value(true))
                .andExpect(jsonPath("$.data.movableItems[1].currentMethod").value("ACCOUNT"))
                .andExpect(jsonPath("$.data.movableItems[1].recognizedAmount").value(50_000))
                .andExpect(jsonPath("$.data.movableItems[1].deadline").value("2026-11-23"))
                .andExpect(jsonPath("$.data.movableItems[2].cashflowItemId").value(tooSoon))
                .andExpect(jsonPath("$.data.movableItems[2].eligible").value(false));

        mockMvc.perform(get(analysisUrl(card)).param("scenarioMoveItemIds", String.valueOf(telecom))
                        .with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projection.projectedRecognizedAmount").value(310_000))
                .andExpect(jsonPath("$.data.guide").value("KEEP_NATURAL"))
                .andExpect(jsonPath("$.data.scenario.moveItemIds[0]").value(telecom))
                .andExpect(jsonPath("$.data.scenario.movedAmount").value(50_000))
                .andExpect(jsonPath("$.data.movableItems", hasSize(2)));
        assertThat(jdbc.queryForObject("SELECT user_card_id FROM cashflow_item WHERE cashflow_item_id = ?",
                Long.class, telecom)).isNull();
    }

    @Test
    void 옮길_수_없거나_남의_예정_지출은_가정할_수_없다() throws Exception {
        history(card, 60_000);
        performance(card, NOVEMBER, 200_000);
        long tooSoon = cashflow(owner, "인터넷", 30_000, LocalDate.of(2026, 11, 11), account, null, null);
        long othersAccount = fixtures.account(other);
        long othersItem = cashflow(other, "통신비", 50_000, LocalDate.of(2026, 11, 25), othersAccount, null, null);

        for (String bad : new String[] {String.valueOf(tooSoon), String.valueOf(othersItem), "abc", "0", "1,,2",
                "-3"}) {
            mockMvc.perform(get(analysisUrl(card)).param("scenarioMoveItemIds", bad).with(AuthUsers.asUser(owner)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("scenarioMoveItemIds"));
        }
    }

    @Test
    void 예정_지출_카드_옮기기는_지금_가계부_수정_API로_되고_다시_분석하면_이_카드_예정_지출이_된다() throws Exception {
        history(card, 60_000);
        performance(card, NOVEMBER, 200_000);
        long telecom = cashflow(owner, "통신비", 50_000, LocalDate.of(2026, 11, 25), account, null, null);

        mockMvc.perform(patch("/api/cashflow-items/" + telecom).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userCardId\":" + card + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userCardId").value(card))
                .andExpect(jsonPath("$.data.accountId").value(nullValue()));

        analysis(owner, card)
                .andExpect(jsonPath("$.data.guide").value("KEEP_NATURAL"))
                .andExpect(jsonPath("$.data.projection.scheduledRecognizable").value(50_000))
                .andExpect(jsonPath("$.data.movableItems", hasSize(0)));
    }

    @Test
    void 더_써야_하는_돈이_혜택보다_크면_추가_소비_비추천이다() throws Exception {
        history(card, 50_000);
        performance(card, NOVEMBER, 100_000);

        analysis(owner, card)
                .andExpect(jsonPath("$.data.guide").value("EXTRA_SPENDING_NOT_RECOMMENDED"))
                .andExpect(jsonPath("$.data.shortfall").value(150_000))
                .andExpect(jsonPath("$.data.lossIfForced").value(140_000))
                .andExpect(jsonPath("$.data.projection.likelihood").value("UNLIKELY"))
                .andExpect(jsonPath("$.data.message", containsString("억지로 채우지 않아도")));
    }

    @Test
    void 목표와_구간은_카드별_실적_현황과_같은_규칙으로_읽는다() throws Exception {
        // 30만(생활 할인)·50만(카페 할인) 상품이 통합 원화 월 한도(30만~50만 1만원·50만~ 2만원)를 함께 쓴다
        long tiered = fixtures.product();
        long life = benefit(tiered, "생활 10% 할인", 300_000);
        long cafe = benefit(tiered, "카페 20% 할인", 500_000);
        long shared = limitGroup(tiered, "SHARED_MONTHLY", null);
        link(tiered, life, shared);
        link(tiered, cafe, shared);
        tier(shared, 300_000, 500_000L, 10_000);
        tier(shared, 500_000, null, 20_000);
        long tieredCard = card(owner, tiered, "ACTIVE");
        history(tieredCard, 20_000);
        performance(tieredCard, NOVEMBER, 350_000);

        mockMvc.perform(get("/api/user-cards/{id}/performance", tieredCard).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.summary.targetAmount").value(500_000));
        analysis(owner, tieredCard)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetAmount").value(500_000))
                .andExpect(jsonPath("$.data.benefitsAtStake[0].name").value("카페 20% 할인"))
                // 예상 37만은 30만 구간(1만원)에 닿으므로 2만원 − 1만원
                .andExpect(jsonPath("$.data.extraBenefitIfMet").value(10_000))
                .andExpect(jsonPath("$.data.guide").value("EXTRA_SPENDING_NOT_RECOMMENDED"));
    }

    // ── 카드별 실적 목록(S15P31E103-373)의 guide·decision ──

    @Test
    void 목록의_guide는_분석과_같은_판정이고_판정할_수_없으면_INSUFFICIENT_DATA다() throws Exception {
        history(card, 50_000);
        performance(card, NOVEMBER, 100_000);                 // 분석: 추가 소비 비추천
        long achieved = card(owner, product, "ACTIVE");
        performance(achieved, NOVEMBER, 300_000);
        long noRow = card(owner, product, "ACTIVE");            // 이번 달 실적 행 없음
        long noHistory = card(owner, product, "ACTIVE");        // 지난 3개월 행 없음 → 분석 422
        performance(noHistory, NOVEMBER, 100_000);
        long noTarget = card(owner, fixtures.product(), "ACTIVE");
        performance(noTarget, NOVEMBER, 100_000);

        mockMvc.perform(get(LIST).with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(item(card, "guide")).value(contains("EXTRA_SPENDING_NOT_RECOMMENDED")))
                .andExpect(jsonPath(item(achieved, "guide")).value(contains("ALREADY_ACHIEVED")))
                .andExpect(jsonPath(item(noRow, "guide")).value(contains("INSUFFICIENT_DATA")))
                .andExpect(jsonPath(item(noHistory, "guide")).value(contains("INSUFFICIENT_DATA")))
                .andExpect(jsonPath(item(noTarget, "guide")).value(contains(nullValue())));
        analysis(owner, card).andExpect(jsonPath("$.data.guide").value("EXTRA_SPENDING_NOT_RECOMMENDED"));
    }

    @Test
    void 목록의_decision은_그달_판단_기록이고_되돌리면_null이며_지난_달은_guide가_없다() throws Exception {
        history(card, 50_000);
        performance(card, NOVEMBER, 100_000);
        decide(owner, card, "{\"decision\":\"SKIP\",\"muteNotifications\":true}").andExpect(status().isOk());

        mockMvc.perform(get(LIST).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath(item(card, "decision.decision")).value(contains("SKIP")))
                .andExpect(jsonPath(item(card, "decision.notificationsMuted")).value(contains(true)))
                .andExpect(jsonPath(item(card, "decision.decidedAt")).value(contains("2026-11-10T03:00:00Z")));
        mockMvc.perform(get(LIST).param("yearMonth", "2026-10").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath(item(card, "guide")).value(contains(nullValue())))
                .andExpect(jsonPath(item(card, "decision")).value(contains(nullValue())));

        decide(owner, card, "{\"decision\":null}").andExpect(status().isOk());
        mockMvc.perform(get(LIST).with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath(item(card, "decision")).value(contains(nullValue())));
    }

    // ── 판단 기록 ──

    @Test
    void 기록하면_분석에_보이고_같은_값을_다시_보내도_한_줄이며_되돌리면_비운다() throws Exception {
        history(card, 50_000);
        performance(card, NOVEMBER, 100_000);

        decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"2026-11-10T02:59:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value("2026-11"))
                .andExpect(jsonPath("$.data.decision").value("SKIP"))
                .andExpect(jsonPath("$.data.guide").value("EXTRA_SPENDING_NOT_RECOMMENDED"))
                .andExpect(jsonPath("$.data.decidedAt").value("2026-11-10T03:00:00Z"))
                .andExpect(jsonPath("$.data.effects.notificationsMuted").value(true))
                .andExpect(jsonPath("$.data.effects.homeTodoRemoved").value(true))
                .andExpect(jsonPath("$.data.canRevert").value(true));
        long version = jdbc.queryForObject("SELECT version FROM performance_decision WHERE user_card_id = ?",
                Long.class, card);

        // 연타: 같은 값이면 바꾸지 않는다
        decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"2026-11-10T02:59:00Z\"}")
                .andExpect(status().isOk());
        assertThat(decisionRows(card)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM performance_decision WHERE user_card_id = ?",
                Long.class, card)).isEqualTo(version);

        analysis(owner, card)
                .andExpect(jsonPath("$.data.decision.decision").value("SKIP"))
                .andExpect(jsonPath("$.data.decision.analysisVersion").value("2026-11-10T02:59:00Z"))
                .andExpect(jsonPath("$.data.decision.notificationsMuted").value(true));

        decide(owner, card, "{\"decision\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value(nullValue()))
                .andExpect(jsonPath("$.data.effects.notificationsMuted").value(false))
                .andExpect(jsonPath("$.data.effects.homeTodoRemoved").value(false))
                .andExpect(jsonPath("$.data.canRevert").value(false));
        assertThat(decisionRows(card)).isEqualTo(1);
        analysis(owner, card).andExpect(jsonPath("$.data.decision").value(nullValue()));
    }

    @Test
    void 다른_판단으로_덮어쓰고_알림_멈춤을_끌_수_있다() throws Exception {
        decide(owner, card, "{\"decision\":\"KEEP\"}").andExpect(status().isOk());
        decide(owner, card, "{\"decision\":\"FILL\",\"muteNotifications\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("FILL"))
                .andExpect(jsonPath("$.data.effects.notificationsMuted").value(false))
                .andExpect(jsonPath("$.data.effects.homeTodoRemoved").value(false));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT decision, guide, notifications_muted, performance_month FROM performance_decision WHERE user_card_id = ?",
                card);
        assertThat(row.get("decision")).isEqualTo("FILL");
        // 이번 달 실적 행이 없어 판정할 수 없어도 기록은 받는다
        assertThat(row.get("guide")).isNull();
        assertThat(row.get("notifications_muted")).isEqualTo(false);
        assertThat(row.get("performance_month").toString()).isEqualTo("2026-11-01");
    }

    @Test
    void decision_칸이_없거나_FILL_KEEP_SKIP이_아니면_400이다() throws Exception {
        for (String bad : new String[] {"{}", "{\"decision\":1}", "{\"decision\":\"MAYBE\"}",
                "{\"decision\":\"skip\"}", "{\"decision\":[\"SKIP\"]}"}) {
            decide(owner, card, bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("decision"));
        }
        assertThat(decisionRows(card)).isZero();
    }

    @Test
    void 같은_달의_옛_분석은_받고_지난_달_분석과_지난_달_기록은_409다() throws Exception {
        // 한국 11월 1일 0시(UTC 10월 31일 15시) 분석은 이번 달
        decide(owner, card, "{\"decision\":\"KEEP\",\"analysisVersion\":\"2026-10-31T15:00:00Z\"}")
                .andExpect(status().isOk());
        decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"2026-10-31T14:59:59Z\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MONTH_CLOSED"));
        decide(owner, card, "{\"decision\":\"SKIP\",\"yearMonth\":\"2026-10\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MONTH_CLOSED"));
        decide(owner, card, "{\"decision\":\"SKIP\",\"yearMonth\":\"2026-12\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("yearMonth"));
        decide(owner, card, "{\"decision\":\"SKIP\",\"yearMonth\":\"2026-11\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT decision FROM performance_decision WHERE user_card_id = ?",
                String.class, card)).isEqualTo("SKIP");
    }

    @Test
    void 분석_버전_형식이_틀리거나_미래면_400이다() throws Exception {
        for (String bad : new String[] {"yesterday", "2026-11-10", "2026-11-10T03:06:00Z"}) {
            decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("analysisVersion"));
        }
        // 기기 시계가 조금 빠른 것(5분 안)은 받고, Z 없는 같은 모양도 UTC로 읽는다
        decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"2026-11-10T03:05:00Z\"}")
                .andExpect(status().isOk());
        decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"2026-11-10T03:00:00\"}")
                .andExpect(status().isOk());
        decide(owner, card, "{\"decision\":\"SKIP\",\"analysisVersion\":\"" + "x".repeat(41) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 첫_기록이_동시에_와도_한_줄만_생기고_둘_다_200이다() throws Exception {
        List<Integer> statuses = Concurrently.run(List.of(
                () -> decide(owner, card, "{\"decision\":\"SKIP\"}").andReturn().getResponse().getStatus(),
                () -> decide(owner, card, "{\"decision\":\"KEEP\"}").andReturn().getResponse().getStatus(),
                () -> decide(owner, card, "{\"decision\":\"SKIP\"}").andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnly(200);
        assertThat(decisionRows(card)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT decision FROM performance_decision WHERE user_card_id = ?",
                String.class, card)).isIn("SKIP", "KEEP");
    }

    @Test
    void 카드_행이_지워지면_판단도_같이_지워진다() throws Exception {
        long temp = card(owner, product, "ACTIVE");
        decide(owner, temp, "{\"decision\":\"SKIP\"}").andExpect(status().isOk());

        jdbc.update("DELETE FROM user_card WHERE user_card_id = ?", temp);

        assertThat(decisionRows(temp)).isZero();
    }

    // ── 픽스처 ──

    static final String LIST = "/api/users/me/card-performances";

    /** 목록에서 그 카드의 칸(JSONPath 필터라 결과는 한 칸짜리 배열). */
    private static String item(long userCardId, String field) {
        return "$.data.cards[?(@.userCardId == " + userCardId + ")]." + field;
    }

    private String analysisUrl(long userCardId) {
        return "/api/user-cards/" + userCardId + "/performance/analysis";
    }

    private String decisionUrl(long userCardId) {
        return "/api/user-cards/" + userCardId + "/performance/decisions";
    }

    private ResultActions analysis(long user, long userCardId) throws Exception {
        return mockMvc.perform(get(analysisUrl(userCardId)).with(AuthUsers.asUser(user)));
    }

    private ResultActions decide(long user, long userCardId, String body) throws Exception {
        return mockMvc.perform(post(decisionUrl(userCardId)).with(AuthUsers.asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expect422(long userCardId) throws Exception {
        analysis(owner, userCardId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DATA_INSUFFICIENT"));
    }

    private long decisionRows(long userCardId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM performance_decision WHERE user_card_id = ?", Long.class,
                userCardId);
    }

    private long card(long user, long cardProduct, String status) {
        long id = fixtures.card(user, cardProduct, null, status);
        jdbc.update("UPDATE user_card SET registered_at = ? WHERE user_card_id = ?", LocalDateTime.now(ZoneOffset.UTC),
                id);
        return id;
    }

    /** 9·10월 실적 행과 각 달 11일 이후 인정 결제 하나(amount). 8월은 행이 없다. */
    private void history(long userCardId, long amount) {
        for (YearMonth month : new YearMonth[] {YearMonth.of(2026, 9), YearMonth.of(2026, 10)}) {
            performance(userCardId, month, amount);
            payment(owner, userCardId, month.atDay(20), "APPROVED", amount);
        }
    }

    private void performance(long userCardId, YearMonth month, long amount) {
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, userCardId, Date.valueOf(month.atDay(1)), amount, now(), now());
    }

    /** 한국 날짜 day 정오 승인 결제. 인정액 null은 미확인. */
    private void payment(long user, long userCardId, LocalDate day, String status, Long recognized) {
        LocalDateTime approvedAt = day.atTime(12, 0).atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Map<String, Object> row = new HashMap<>(timestamps(Map.of("user_id", user, "merchant_id", merchant,
                "payment_channel", "OFFLINE", "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", new byte[32], "source", "YAMU", "status", status,
                "requested_amount", 30_000, "installment_months", 1, "requested_at", approvedAt.minusSeconds(2))));
        row.put("user_card_id", userCardId);
        row.put("card_product_id", jdbc.queryForObject(
                "SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class, userCardId));
        row.put("approved_at", approvedAt);
        row.put("approved_amount", 30_000);
        row.put("performance_month", Date.valueOf(day.withDayOfMonth(1)));
        if (recognized != null) {
            row.put("recognized_performance_amount", recognized);
        }
        if (status.equals("CANCELED")) {
            row.put("canceled_at", approvedAt.plusMinutes(1));
        }
        insert("payment_transaction", "payment_transaction_id", row);
    }

    /** 가계부 등록 API로 1회 지출 항목을 만든다. 계좌나 카드 중 하나. */
    private long cashflow(long user, String title, long amount, LocalDate date, Long accountId, Long userCardId,
            String category) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "EXPENSE", "title", title, "amount", amount,
                "date", date.toString()));
        if (accountId != null) {
            body.put("accountId", accountId);
        }
        if (userCardId != null) {
            body.put("userCardId", userCardId);
        }
        if (category != null) {
            body.put("merchantCategoryCode", category);
        }
        String response = mockMvc.perform(post("/api/cashflow-items").with(AuthUsers.asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.path("data").path("cashflowItemId").asLong();
    }

    private long benefit(long cardProduct, String name, long minPerformance) {
        return insert("card_benefit", "card_benefit_id", Map.of("card_product_id", cardProduct,
                "benefit_code", "B-" + UUID.randomUUID(), "policy_version", 1, "benefit_name", name,
                "benefit_type", "DISCOUNT", "calculation_type", "RATE", "rate", new BigDecimal("0.1"),
                "value_unit", "KRW", "min_performance_amount", minPerformance, "created_at", now()));
    }

    /** 원화 월 한도 그룹. baseLimit이 null이면 구간 행(tier_limit)으로 한도를 둔다. */
    private long limitGroup(long cardProduct, String code, Long baseLimit) {
        Map<String, Object> row = new HashMap<>(Map.of("card_product_id", cardProduct, "group_code", code,
                "policy_version", 1, "group_name", code, "period_type", "MONTHLY", "limit_unit", "KRW",
                "created_at", now()));
        if (baseLimit != null) {
            row.put("base_limit_value", baseLimit);
        }
        return insert("limit_group", "limit_group_id", row);
    }

    private void link(long cardProduct, long benefit, long group) {
        insert("benefit_limit_link", "benefit_limit_link_id", Map.of("card_product_id", cardProduct,
                "card_benefit_id", benefit, "limit_group_id", group, "created_at", now()));
    }

    private void tier(long group, long min, Long max, long limit) {
        Map<String, Object> row = new HashMap<>(Map.of("limit_group_id", group, "min_performance_amount", min,
                "limit_value", limit, "created_at", now()));
        if (max != null) {
            row.put("max_performance_amount", max);
        }
        insert("tier_limit", "tier_limit_id", row);
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
}
