package com.yamu.be.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.performance.CardPerformancesResponse;
import com.yamu.be.performance.PerformanceService;
import com.yamu.be.performance.PerformanceStatus;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 홈 요약({@code GET /api/home})과 동기화({@code POST /api/sync})를 인증 필터·실제 MySQL·Redis까지 묶어 확인한다.
 * 잔액(BE 1, 금융망)과 카드별 실적(373)은 가짜로 둔다. 실적 상태는 날짜(이번 달 첫 5일은 ON_TRACK)에 따라 달라 실제 값으로는
 * AT_RISK를 늘 만들 수 없고, 실적 판정 자체는 PerformanceIntegrationTest가 확인한다. 요청을 여러 스레드에서 보내는 테스트가 있어 트랜잭션으로 감싸지 않고, 테스트마다 새 사용자를 만든다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-home-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HomeIntegrationTest extends MySqlTestSupport {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @MockitoBean AccountBalanceService balances;
    @MockitoBean PerformanceService performances;

    OwnershipFixtures fixtures;
    LocalDateTime fetchedAt;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        fetchedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).minusMinutes(3);
        when(balances.getBalances(anyLong(), anyList(), anyBoolean())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.stream().map(id -> new AccountBalance(id, 1_000_000L, fetchedAt)).toList();
        });
        when(performances.overview(anyLong(), isNull()))
                .thenReturn(new CardPerformancesResponse(YearMonth.now(KOREA).toString(), 11, List.of(), null));
    }

    @Test
    void 실적_부족_카드는_performanceAlerts에_나오고_할_일에서_미납_다음이다() throws Exception {
        long owner = fixtures.user();
        long account = primaryAccount(owner);
        long unpaidCard = creditCard(owner, account, "ACTIVE");
        long onTrack = creditCard(owner, account, "ACTIVE");
        long atRisk = creditCard(owner, account, "ACTIVE");
        long atRiskToo = creditCard(owner, account, "ACTIVE");
        unpaidBill(owner, unpaidCard);
        when(performances.overview(eq(owner), isNull())).thenReturn(new CardPerformancesResponse(
                YearMonth.now(KOREA).toString(), 11, List.of(
                        performance(onTrack, "넉넉", PerformanceStatus.ON_TRACK, 300_000L, 290_000L),
                        performance(atRisk, "딥드림", PerformanceStatus.AT_RISK, 300_000L, 120_000L),
                        performance(atRiskToo, "탄탄대로", PerformanceStatus.AT_RISK, 500_000L, 0L)),
                null));

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partialErrors").isEmpty())
                .andExpect(jsonPath("$.data.performanceAlerts.length()").value(2))
                .andExpect(jsonPath("$.data.performanceAlerts[0].userCardId").value(atRisk))
                .andExpect(jsonPath("$.data.performanceAlerts[0].nickname").value("딥드림"))
                .andExpect(jsonPath("$.data.performanceAlerts[0].remainingAmount").value(180_000))
                .andExpect(jsonPath("$.data.performanceAlerts[0].achievementRate").value(40.0))
                .andExpect(jsonPath("$.data.performanceAlerts[0].daysLeft").value(11))
                .andExpect(jsonPath("$.data.performanceAlerts[1].userCardId").value(atRiskToo))
                .andExpect(jsonPath("$.data.todos.length()").value(2))
                .andExpect(jsonPath("$.data.todos[0].userCardId").value(unpaidCard))
                .andExpect(jsonPath("$.data.todos[0].tone").value("ERROR"))
                .andExpect(jsonPath("$.data.todos[1].userCardId").value(atRisk))
                .andExpect(jsonPath("$.data.todos[1].title").value("딥드림 실적까지 180,000원 남았어요"))
                .andExpect(jsonPath("$.data.todos[1].tag").value("마감 D-11"))
                .andExpect(jsonPath("$.data.todos[1].tone").value("WARNING"));
    }

    @Test
    void 실적을_못_읽어도_미납_할_일과_나머지_영역은_나온다() throws Exception {
        long owner = fixtures.user();
        long account = primaryAccount(owner);
        long card = creditCard(owner, account, "ACTIVE");
        unpaidBill(owner, card);
        when(performances.overview(eq(owner), isNull())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partialErrors.length()").value(1))
                .andExpect(jsonPath("$.data.partialErrors[0].area").value("performanceAlerts"))
                .andExpect(jsonPath("$.data.partialErrors[0].code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data.performanceAlerts").isEmpty())
                .andExpect(jsonPath("$.data.todos.length()").value(1))
                .andExpect(jsonPath("$.data.todos[0].userCardId").value(card))
                .andExpect(jsonPath("$.data.cards.count").value(1));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/home")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    void 홈은_카드_잔고예보_알림_할일_최근결제를_한번에_돌려준다() throws Exception {
        long owner = fixtures.user();
        long account = primaryAccount(owner);
        long card = creditCard(owner, account, "ACTIVE");
        long merchant = merchant("COFFEE_SHOP", "야무카페 역삼점");
        unpaidBill(owner, card);
        notification(owner, false);
        notification(owner, false);
        notification(owner, true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        payment(owner, card, merchant, "APPROVED", 4_500, now.minusHours(2), null);
        long latest = payment(owner, card, merchant, "APPROVED", 15_000, now.minusHours(1), null);
        payment(owner, card, merchant, "CANCELED", 9_000, now.minusMinutes(10), null);

        // 다른 사용자의 알림·더 최근 결제는 보이지 않는다.
        long stranger = fixtures.user();
        long strangerCard = creditCard(stranger, fixtures.account(stranger), "ACTIVE");
        notification(stranger, false);
        payment(stranger, strangerCard, merchant, "APPROVED", 99_000, now.minusMinutes(1), null);

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.partialErrors").isEmpty())
                .andExpect(jsonPath("$.data.cards.count").value(1))
                .andExpect(jsonPath("$.data.cards.items[0].userCardId").value(card))
                .andExpect(jsonPath("$.data.cards.items[0].cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.cards.items[0].maskedNumber").exists())
                .andExpect(jsonPath("$.data.performanceAlerts").isEmpty())
                .andExpect(jsonPath("$.data.monthSummary.yearMonth").value(YearMonth.now(KOREA).toString()))
                .andExpect(jsonPath("$.data.monthSummary.totalSpent")
                        .value(thisMonth(now.minusHours(2), 4_500) + thisMonth(now.minusHours(1), 15_000)))
                .andExpect(jsonPath("$.data.monthSummary.totalBenefit").value(0))
                .andExpect(jsonPath("$.data.balanceForecast.accountId").value(account))
                .andExpect(jsonPath("$.data.balanceForecast.currentBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.balanceForecast.horizonDays").isNumber())
                .andExpect(jsonPath("$.data.balanceForecast.minBalance").isNumber())
                .andExpect(jsonPath("$.data.balanceForecast.isEstimate").value(true))
                .andExpect(jsonPath("$.data.dataSyncedAt").value(fetchedAt + "Z"))
                .andExpect(jsonPath("$.data.unreadNotificationCount").value(2))
                .andExpect(jsonPath("$.data.todos.length()").value(1))
                .andExpect(jsonPath("$.data.todos[0].tag").value("미납"))
                .andExpect(jsonPath("$.data.todos[0].tone").value("ERROR"))
                .andExpect(jsonPath("$.data.todos[0].userCardId").value(card))
                .andExpect(jsonPath("$.data.recentPayment.paymentId").value(latest))
                .andExpect(jsonPath("$.data.recentPayment.merchantName").value("야무카페 역삼점"))
                .andExpect(jsonPath("$.data.recentPayment.amount").value(15_000))
                .andExpect(jsonPath("$.data.onboarding.step").value("DONE"));
        verify(balances).getBalances(eq(owner), eq(List.of(account)), eq(false));
    }

    @Test
    void 잔액을_못_읽어도_나머지_영역은_200으로_돌려준다() throws Exception {
        long owner = fixtures.user();
        long account = primaryAccount(owner);
        long card = creditCard(owner, account, "ACTIVE");
        notification(owner, false);
        when(balances.getBalances(eq(owner), anyList(), anyBoolean()))
                .thenThrow(FinApiException.timedOut("inquireDemandDepositAccountBalance"));

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.partialErrors.length()").value(1))
                .andExpect(jsonPath("$.data.partialErrors[0].area").value("balanceForecast"))
                .andExpect(jsonPath("$.data.partialErrors[0].code").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.data.balanceForecast").doesNotExist())
                .andExpect(jsonPath("$.data.dataSyncedAt").doesNotExist())
                .andExpect(jsonPath("$.data.cards.items[0].userCardId").value(card))
                .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
                .andExpect(jsonPath("$.data.onboarding.step").value("DONE"));
    }

    @Test
    void 연결_계좌가_없으면_LINK이고_예보는_실패가_아니라_비어_있다() throws Exception {
        long owner = fixtures.user();

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partialErrors").isEmpty())
                .andExpect(jsonPath("$.data.cards.count").value(0))
                .andExpect(jsonPath("$.data.balanceForecast").doesNotExist())
                .andExpect(jsonPath("$.data.onboarding.step").value("LINK"))
                .andExpect(jsonPath("$.data.todos").isEmpty())
                .andExpect(jsonPath("$.data.recentPayment").doesNotExist())
                .andExpect(jsonPath("$.data.todayCard").doesNotExist())
                .andExpect(jsonPath("$.data.unreadNotificationCount").value(0));
        verify(balances, never()).getBalances(eq(owner), anyList(), anyBoolean());
    }

    @Test
    void 오늘의_카드는_자주_쓴_업종에서_1순위로_가장_많이_나온_쓸_수_있는_카드다() throws Exception {
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        long cardA = creditCard(owner, account, "ACTIVE");
        long cardB = creditCard(owner, account, "ACTIVE");
        long inactive = creditCard(owner, account, "INACTIVE");
        jdbc.update("UPDATE user_card SET card_alias = '야무 B카드' WHERE user_card_id = ?", cardB);
        long cafe = merchant("COFFEE_SHOP", "카페");
        long store = merchant("CONVENIENCE_STORE", "편의점");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 카페 5건(가장 많이 쓴 업종): B 1순위 2건, A 1순위 1건, 정지 카드 1순위 2건(세지 않음)
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(1), recommendation(cardB, 500));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(2), recommendation(cardB, 500));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(3), recommendation(cardA, 300));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(4), recommendation(inactive, 900));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(5), recommendation(inactive, 900));
        // 순혜택 0원인 1순위는 세지 않는다. 30일 지난 결제도 세지 않는다.
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(6), recommendation(cardA, 0));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(6), recommendation(cardA, 0));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(40), recommendation(cardA, 700));
        payment(owner, cardA, cafe, "APPROVED", 5_000, now.minusDays(41), recommendation(cardA, 700));
        // 편의점 4건: 건수가 카페(7)보다 적다
        for (int i = 0; i < 4; i++) {
            payment(owner, cardA, store, "APPROVED", 3_000, now.minusHours(i + 1), recommendation(cardA, 300));
        }

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayCard.userCardId").value(cardB))
                .andExpect(jsonPath("$.data.todayCard.cardName").value("야무 B카드"))
                .andExpect(jsonPath("$.data.todayCard.benefit").value("카페 결제에 혜택이 가장 커요"));
    }

    @Test
    void 가장_많이_쓴_업종에_추천_결과가_없으면_오늘의_카드는_없다() throws Exception {
        long owner = fixtures.user();
        long card = creditCard(owner, fixtures.account(owner), "ACTIVE");
        long cafe = merchant("COFFEE_SHOP", "카페");
        long store = merchant("CONVENIENCE_STORE", "편의점");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        payment(owner, card, cafe, "APPROVED", 5_000, now.minusDays(1), null);
        payment(owner, card, cafe, "APPROVED", 5_000, now.minusDays(2), null);
        payment(owner, card, store, "APPROVED", 3_000, now.minusDays(3), recommendation(card, 300));

        mockMvc.perform(get("/api/home").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partialErrors").isEmpty())
                .andExpect(jsonPath("$.data.todayCard").doesNotExist());
    }

    @Test
    void 동기화는_30초_안에_다시_부르면_금융망을_다시_부르지_않는다() throws Exception {
        long owner = fixtures.user();
        long first = fixtures.account(owner);
        long second = fixtures.account(owner);
        long other = fixtures.user();
        long otherAccount = fixtures.account(other);
        when(balances.getBalanceResults(anyLong(), anyList(), eq(true))).thenAnswer(invocation -> ok(invocation.getArgument(1)));

        JsonNode a = sync(owner);
        JsonNode b = sync(owner);
        JsonNode c = sync(other);

        assertThat(a.path("failed")).isEmpty();
        assertThat(a.path("newTransactions").asInt()).isZero();
        assertThat(b).isEqualTo(a);
        assertThat(c.path("syncedAt").isTextual()).isTrue();
        verify(balances, times(1)).getBalanceResults(owner, List.of(first, second), true);
        verify(balances, times(1)).getBalanceResults(other, List.of(otherAccount), true);
    }

    @Test
    void 동시에_두번_누르면_금융망은_한번만_부르고_다른_하나는_429다() throws Exception {
        long owner = fixtures.user();
        fixtures.account(owner);
        when(balances.getBalanceResults(eq(owner), anyList(), eq(true))).thenAnswer(invocation -> {
            Thread.sleep(500);
            return ok(invocation.getArgument(1));
        });

        List<Integer> statuses = Concurrently.run(2, () -> mockMvc.perform(post("/api/sync")
                .with(AuthUsers.asUser(owner))).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 429);
        verify(balances, times(1)).getBalanceResults(eq(owner), anyList(), eq(true));
        // 끝난 뒤에는 지난 결과를 준다.
        assertThat(sync(owner).path("syncedAt").isTextual()).isTrue();
        verify(balances, times(1)).getBalanceResults(eq(owner), anyList(), eq(true));
    }

    @Test
    void 다른_요청이_동기화_중이면_429와_다시_시도할_초를_준다() throws Exception {
        long owner = fixtures.user();
        redis.opsForValue().set("home:sync:lock:" + owner, "someone-else", SyncService.LOCK_TTL);

        mockMvc.perform(post("/api/sync").with(AuthUsers.asUser(owner)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
        verify(balances, never()).getBalanceResults(eq(owner), anyList(), anyBoolean());
    }

    @Test
    void 동기화는_실패한_계좌만_failed에_적고_200이다() throws Exception {
        long owner = fixtures.user();
        long good = fixtures.account(owner);
        long bad = fixtures.account(owner);
        when(balances.getBalanceResults(eq(owner), anyList(), eq(true))).thenReturn(Map.of(
                good, new AccountBalanceService.BalanceRead(new AccountBalance(good, 5_000L, fetchedAt), null),
                bad, new AccountBalanceService.BalanceRead(null, ErrorCode.FIN_API_UNAVAILABLE)));

        mockMvc.perform(post("/api/sync").with(AuthUsers.asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newTransactions").value(0))
                .andExpect(jsonPath("$.data.failed.length()").value(1))
                .andExpect(jsonPath("$.data.failed[0].accountId").value(bad))
                .andExpect(jsonPath("$.data.failed[0].code").value("FIN_API_UNAVAILABLE"));
    }

    @Test
    void 연결_계좌가_없으면_금융망을_부르지_않고_빈_결과다() throws Exception {
        long owner = fixtures.user();

        JsonNode result = sync(owner);

        assertThat(result.path("failed")).isEmpty();
        verify(balances, never()).getBalanceResults(eq(owner), anyList(), anyBoolean());
    }

    private JsonNode sync(long userId) throws Exception {
        String body = mockMvc.perform(post("/api/sync").with(AuthUsers.asUser(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private Map<Long, AccountBalanceService.BalanceRead> ok(List<Long> ids) {
        Map<Long, AccountBalanceService.BalanceRead> results = new LinkedHashMap<>();
        ids.forEach(id -> results.put(id,
                new AccountBalanceService.BalanceRead(new AccountBalance(id, 1_000L, fetchedAt), null)));
        return results;
    }

    private static CardPerformancesResponse.Item performance(long card, String nickname, PerformanceStatus status,
            long target, long recognized) {
        return new CardPerformancesResponse.Item(card, nickname, null, null, "CREDIT", target, null, recognized,
                Math.max(target - recognized, 0), status, null, null, false, null);
    }

    /** 한국 기준 이번 달 결제면 금액, 아니면 0. 매달 1일 0~2시(한국)에 돌려도 월간 리포트와 같은 기준으로 맞춘다. */
    private static long thisMonth(LocalDateTime requestedAtUtc, long amount) {
        YearMonth month = YearMonth.from(requestedAtUtc.atZone(ZoneOffset.UTC).withZoneSameInstant(KOREA));
        return month.equals(YearMonth.now(KOREA)) ? amount : 0;
    }

    private long primaryAccount(long owner) {
        long account = fixtures.account(owner);
        jdbc.update("UPDATE linked_account SET is_primary = TRUE WHERE linked_account_id = ?", account);
        return account;
    }

    private long creditCard(long owner, long account, String status) {
        long card = fixtures.card(owner, fixtures.product(), account, status);
        jdbc.update("UPDATE user_card SET payment_day = 14 WHERE user_card_id = ?", card);
        return card;
    }

    private void unpaidBill(long owner, long card) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDate month = LocalDate.now(KOREA).withDayOfMonth(1).minusMonths(1);
        new SimpleJdbcInsert(jdbc).withTableName("card_bill")
                .usingColumns("user_id", "user_card_id", "billing_month", "period_start", "period_end",
                        "withdrawal_date", "status", "created_at", "updated_at")
                .execute(Map.of("user_id", owner, "user_card_id", card, "billing_month", month,
                        "period_start", month.minusMonths(1), "period_end", month.minusDays(1),
                        "withdrawal_date", month.withDayOfMonth(14), "status", "UNPAID", "created_at", now,
                        "updated_at", now));
    }

    private void notification(long owner, boolean read) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("user_id", owner, "notification_type", "PAYMENT_RESULT",
                "deduplication_key", UUID.randomUUID().toString(), "title", "결제 완료", "body", "결제가 끝났어요",
                "created_at", now, "updated_at", now));
        if (read) {
            row.put("read_at", now);
        }
        new SimpleJdbcInsert(jdbc).withTableName("notification").usingColumns(row.keySet().toArray(String[]::new))
                .execute(row);
    }

    private long merchant(String category, String name) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("merchant").usingGeneratedKeyColumns("merchant_id")
                .usingColumns("merchant_name", "brand_code", "merchant_category_code", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("merchant_name", name, "brand_code", "HOME_" + UUID.randomUUID(),
                        "merchant_category_code", category, "created_at", now, "updated_at", now))
                .longValue();
    }

    private long payment(long owner, long card, long merchant, String status, long amount, LocalDateTime requestedAt,
            String recommendation) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?",
                Long.class, card);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_id", owner);
        row.put("merchant_id", merchant);
        row.put("user_card_id", card);
        row.put("card_product_id", product);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", hash());
        row.put("source", "YAMU");
        row.put("status", status);
        row.put("requested_amount", amount);
        row.put("approved_amount", amount);
        row.put("installment_months", 1);
        row.put("recognized_performance_amount", 0L);
        row.put("performance_month", requestedAt.atZone(ZoneOffset.UTC).withZoneSameInstant(KOREA).toLocalDate()
                .withDayOfMonth(1));
        row.put("requested_at", requestedAt);
        row.put("approved_at", requestedAt);
        if ("CANCELED".equals(status)) {
            row.put("canceled_at", now);
        }
        if (recommendation != null) {
            row.put("recommendation_result_json", recommendation);
        }
        row.put("created_at", now);
        row.put("updated_at", now);
        return new SimpleJdbcInsert(jdbc).withTableName("payment_transaction")
                .usingGeneratedKeyColumns("payment_transaction_id")
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    /** 저장된 추천 결과 중 홈이 읽는 칸(1순위 카드·순혜택)만 채운 것. */
    private static String recommendation(long topCard, long netBenefit) {
        return """
                {"schemaVersion":1,"candidates":[{"card":{"userCardId":%d},"netBenefit":%d,"unallocatedBenefitGroups":[]}]}
                """.formatted(topCard, netBenefit).strip();
    }

    private static byte[] hash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
