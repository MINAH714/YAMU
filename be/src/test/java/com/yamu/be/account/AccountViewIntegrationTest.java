package com.yamu.be.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.account.SsafyAccountApi.Balance;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiFailures;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 계좌 목록·정보 수정과 잔고예보용 잔액 조회(getBalances)를 실제 MySQL·Redis로 확인한다. SSAFY 금융망은 가짜다.
 * 연결된 계좌는 계좌 연결을 마친 상태처럼 linked_account 행으로 바로 넣는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-account-view-integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountViewIntegrationTest extends MySqlTestSupport {

    private static final String USER_KEY = "2695628f-11e1-418e-b533-9ae19e0650ec";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired AccountBalanceService balanceService;

    @MockitoBean FinApiClient finApiClient;

    /** 계좌번호 끝 4자리 → SSAFY 잔액. 없는 번호는 금융망 실패. */
    final Map<String, Object> balances = new HashMap<>();

    long owner;
    long deviceId;
    long first;
    long second;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        balances.clear();
        balances.put("6547", 620000L);
        balances.put("1234", 1250000L);
        when(finApiClient.post(eq(SsafyAccountApi.BALANCE_PATH), eq(USER_KEY), any(), eq(Balance.class)))
                .thenAnswer(inv -> {
                    String accountNo = (String) ((Map<?, ?>) inv.getArgument(2)).get("accountNo");
                    Object value = balances.get(accountNo.substring(12));
                    if (value instanceof FinApiException e) {
                        throw e;
                    }
                    if (value == null) {
                        throw FinApiFailures.timedOut("inquireDemandDepositAccountBalance");
                    }
                    return new Balance((Long) value);
                });

        owner = user("01012345678", true);
        deviceId = insert("user_device", "user_device_id", Map.of("user_id", owner,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID", "public_key", "test-key",
                "key_algorithm", "SHA256withECDSA", "created_at", now(), "updated_at", now()));
        first = account(owner, "6547", "CONNECTED", true, "생활비 통장");
        second = account(owner, "1234", "CONNECTED", false, null);
    }

    // ───────── 목록 ─────────

    @Test
    void 목록은_연결된_내_계좌만_연결_순으로_잔액_기준_계좌_연결_카드_확정_청구와_함께_보인다() throws Exception {
        account(owner, "9999", "DISCONNECTED", false, null);
        long stranger = user("01099998888", true);
        account(stranger, "8888", "CONNECTED", true, null);
        long checkProduct = product("SHINHAN", "신한 카페 체크", "CHECK");
        long creditProduct = product("HANA", "하나 생활 신용", "CREDIT");
        long check = card(checkProduct, "9410123456785678", "ACTIVE", first, "생활비 카드");
        long credit = card(creditProduct, "4570000011111234", "INACTIVE", first, null);
        card(checkProduct, "9410000044445555", "UNREGISTERED", first, null);
        long confirmed = bill(credit, first, "CONFIRMED", LocalDate.now().plusDays(10));
        bill(credit, first, "PAID", LocalDate.now().minusDays(20));

        get("/api/accounts")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[0].accountId").value(first))
                .andExpect(jsonPath("$.data.accounts[0].bank.code").value("088"))
                .andExpect(jsonPath("$.data.accounts[0].bank.name").value("신한은행"))
                .andExpect(jsonPath("$.data.accounts[0].accountName").value("생활비 통장"))
                .andExpect(jsonPath("$.data.accounts[0].maskedAccountNo").value("****-****-****-6547"))
                .andExpect(jsonPath("$.data.accounts[0].balance").value(620000))
                .andExpect(jsonPath("$.data.accounts[0].balanceAsOf").exists())
                .andExpect(jsonPath("$.data.accounts[0].isPrimary").value(true))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards[0].userCardId").value(check))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards[0].nickname").value("생활비 카드"))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards[0].role").value("CHECK_LINKED"))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards[1].userCardId").value(credit))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards[1].nickname").value("하나 생활 신용"))
                .andExpect(jsonPath("$.data.accounts[0].linkedCards[1].role").value("CREDIT_SETTLEMENT"))
                .andExpect(jsonPath("$.data.accounts[0].upcomingWithdrawals.length()").value(1))
                .andExpect(jsonPath("$.data.accounts[0].upcomingWithdrawals[0].billId").value(confirmed))
                .andExpect(jsonPath("$.data.accounts[0].upcomingWithdrawals[0].amount").value(128500))
                .andExpect(jsonPath("$.data.accounts[0].linkedAt").exists())
                .andExpect(jsonPath("$.data.accounts[1].accountId").value(second))
                .andExpect(jsonPath("$.data.accounts[1].accountName").doesNotExist())
                .andExpect(jsonPath("$.data.accounts[1].balance").value(1250000))
                .andExpect(jsonPath("$.data.accounts[1].isPrimary").value(false))
                .andExpect(jsonPath("$.data.accounts[1].linkedCards.length()").value(0))
                .andExpect(jsonPath("$.data.dataSyncedAt").exists());
    }

    @Test
    void 목록_잔액은_10분_캐시를_쓰고_refresh면_다시_읽는다() throws Exception {
        get("/api/accounts").andExpect(status().isOk());
        verify(finApiClient, times(2)).post(eq(SsafyAccountApi.BALANCE_PATH), eq(USER_KEY), any(), eq(Balance.class));

        balances.put("6547", 1L);
        get("/api/accounts").andExpect(jsonPath("$.data.accounts[0].balance").value(620000));
        verify(finApiClient, times(2)).post(eq(SsafyAccountApi.BALANCE_PATH), eq(USER_KEY), any(), eq(Balance.class));

        get("/api/accounts?refresh=true").andExpect(jsonPath("$.data.accounts[0].balance").value(1));
        verify(finApiClient, times(4)).post(eq(SsafyAccountApi.BALANCE_PATH), eq(USER_KEY), any(), eq(Balance.class));
        assertThat(redis.opsForValue().get("account:balance:" + first)).startsWith("1|");
    }

    @Test
    void 목록은_잔액을_못_읽은_계좌만_잔액을_비우고_나머지는_보여_준다() throws Exception {
        balances.put("1234", FinApiFailures.timedOut("inquireDemandDepositAccountBalance"));

        get("/api/accounts")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[0].balance").value(620000))
                .andExpect(jsonPath("$.data.accounts[1].balance").doesNotExist())
                .andExpect(jsonPath("$.data.accounts[1].balanceAsOf").doesNotExist());
    }

    @Test
    void 연결_계좌가_없으면_빈_목록이고_금융망을_부르지_않는다() throws Exception {
        long newbie = user("01055556666", true);
        long newbieDevice = insert("user_device", "user_device_id", Map.of("user_id", newbie,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID", "public_key", "test-key",
                "key_algorithm", "SHA256withECDSA", "created_at", now(), "updated_at", now()));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/accounts").with(AuthUsers.asUser(newbie, newbieDevice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts.length()").value(0))
                .andExpect(jsonPath("$.data.dataSyncedAt").doesNotExist());
        verify(finApiClient, never()).post(eq(SsafyAccountApi.BALANCE_PATH), any(), any(), eq(Balance.class));
    }

    @Test
    void 로그인하지_않으면_목록을_볼_수_없다() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/accounts")).andExpect(errorCode(401, "UNAUTHORIZED"));
    }

    // ───────── 수정 ─────────

    @Test
    void 별칭은_보낸_값으로_바뀌고_빈_문자열이면_지워지며_20자를_넘으면_400이다() throws Exception {
        patch(second, "{\"accountName\":\"  비상금  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(second))
                .andExpect(jsonPath("$.data.accountName").value("비상금"))
                .andExpect(jsonPath("$.data.balance").value(1250000))
                .andExpect(jsonPath("$.data.isPrimary").value(false));

        patch(second, "{\"accountName\":\"\"}").andExpect(jsonPath("$.data.accountName").doesNotExist());
        patch(second, "{\"accountName\":\"" + "가".repeat(21) + "\"}").andExpect(errorCode(400, "INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("accountName"));
        patch(first, "{}").andExpect(jsonPath("$.data.accountName").value("생활비 통장"));
    }

    @Test
    void 기준_계좌를_옮기면_기존_기준_계좌는_풀린다() throws Exception {
        patch(second, "{\"isPrimary\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPrimary").value(true));

        assertThat(primaries()).containsExactly(second);
        patch(second, "{\"isPrimary\":true}").andExpect(jsonPath("$.data.isPrimary").value(true));
        assertThat(primaries()).containsExactly(second);
    }

    @Test
    void 기준_계좌를_false로_풀면_400_PRIMARY_REQUIRED이고_아무것도_바뀌지_않는다() throws Exception {
        patch(first, "{\"accountName\":\"바뀌면 안 됨\",\"isPrimary\":false}")
                .andExpect(errorCode(400, "PRIMARY_REQUIRED"));

        assertThat(primaries()).containsExactly(first);
        assertThat(jdbc.queryForObject("SELECT account_alias FROM linked_account WHERE linked_account_id = ?",
                String.class, first)).isEqualTo("생활비 통장");
        // 기준 계좌가 아닌 계좌에 false는 그대로 둔다.
        patch(second, "{\"isPrimary\":false}").andExpect(jsonPath("$.data.isPrimary").value(false));
    }

    @Test
    void 남의_계좌_해제한_계좌_없는_계좌는_404다() throws Exception {
        long stranger = user("01099998888", true);
        long strangers = account(stranger, "8888", "CONNECTED", true, null);
        long disconnected = account(owner, "9999", "DISCONNECTED", false, null);

        patch(strangers, "{\"isPrimary\":true}").andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        patch(disconnected, "{\"isPrimary\":true}").andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        patch(999999L, "{\"accountName\":\"x\"}").andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        assertThat(primaries()).containsExactly(first);
    }

    @Test
    void 두_계좌를_동시에_기준_계좌로_정해도_기준_계좌는_하나다() throws Exception {
        long third = account(owner, "5555", "CONNECTED", false, null);
        balances.put("5555", 1000L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit((Callable<Integer>) () -> concurrentPatch(start, second));
            Future<Integer> b = pool.submit((Callable<Integer>) () -> concurrentPatch(start, third));
            start.countDown();
            // 사용자 행 잠금으로 차례로 처리되어 둘 다 200, 늦게 처리된 쪽이 기준 계좌로 남는다.
            assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(primaries()).hasSize(1).first().isIn(second, third);
    }

    @Test
    void 기준_계좌는_DB가_사용자당_하나로_막고_해제한_계좌는_기준_계좌가_될_수_없다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE linked_account SET is_primary = 1 WHERE linked_account_id = ?",
                second)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> account(owner, "9999", "DISCONNECTED", true, null))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ───────── 잔고예보용 getBalances ─────────

    @Test
    void getBalances는_넘긴_순서대로_중복을_빼고_돌려준다() {
        List<AccountBalance> result = balanceService.getBalances(owner, List.of(second, first, second), false);

        assertThat(result).extracting(AccountBalance::linkedAccountId).containsExactly(second, first);
        assertThat(result).extracting(AccountBalance::balance).containsExactly(1250000L, 620000L);
        assertThat(result).allSatisfy(b -> assertThat(b.fetchedAt()).isNotNull());
        assertThat(balanceService.getBalances(owner, List.of(), false)).isEmpty();
        assertThat(balanceService.getBalances(owner, null, false)).isEmpty();
    }

    @Test
    void getBalances는_하나라도_남의_계좌_해제_계좌면_금융망을_부르기_전에_전체_404다() {
        long stranger = user("01099998888", true);
        long strangers = account(stranger, "8888", "CONNECTED", true, null);
        long disconnected = account(owner, "9999", "DISCONNECTED", false, null);

        for (List<Long> ids : List.of(List.of(first, strangers), List.of(first, disconnected), List.of(first, 999999L))) {
            assertThatThrownBy(() -> balanceService.getBalances(owner, ids, false))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
        verify(finApiClient, never()).post(eq(SsafyAccountApi.BALANCE_PATH), any(), any(), eq(Balance.class));
    }

    @Test
    void getBalances는_금융망이_한_계좌라도_실패하면_그_예외를_그대로_던진다() {
        FinApiException failure = FinApiFailures.timedOut("inquireDemandDepositAccountBalance");
        balances.put("1234", failure);

        assertThatThrownBy(() -> balanceService.getBalances(owner, List.of(first, second), false)).isSameAs(failure);
    }

    @Test
    void getBalances와_계좌_목록은_같은_캐시를_쓰고_refresh면_새로_읽는다() throws Exception {
        get("/api/accounts").andExpect(status().isOk());
        clearInvocations(finApiClient);
        balances.put("6547", 7000L);

        assertThat(balanceService.getBalances(owner, List.of(first), false))
                .singleElement().extracting(AccountBalance::balance).isEqualTo(620000L);
        verify(finApiClient, never()).post(eq(SsafyAccountApi.BALANCE_PATH), any(), any(), eq(Balance.class));

        assertThat(balanceService.getBalances(owner, List.of(first), true))
                .singleElement().extracting(AccountBalance::balance).isEqualTo(7000L);
        get("/api/accounts").andExpect(jsonPath("$.data.accounts[0].balance").value(7000));
    }

    @Test
    void 캐시에서_꺼낸_잔액은_처음_금융망에서_읽은_시각을_그대로_준다() {
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 30, 1, 0);
        redis.opsForValue().set("account:balance:" + first, "5000|" + readAt.toEpochSecond(ZoneOffset.UTC),
                java.time.Duration.ofMinutes(10));

        assertThat(balanceService.getBalances(owner, List.of(first), false)).singleElement().satisfies(b -> {
            assertThat(b.balance()).isEqualTo(5000L);
            assertThat(b.fetchedAt()).isEqualTo(readAt);
        });
        verify(finApiClient, never()).post(eq(SsafyAccountApi.BALANCE_PATH), any(), any(), eq(Balance.class));
    }

    @Test
    void 금융망에서_읽은_잔액은_10분_TTL로_캐시한다() {
        balanceService.getBalances(owner, List.of(first), false);

        assertThat(redis.getExpire("account:balance:" + first, TimeUnit.SECONDS)).isBetween(590L, 600L);
    }

    @Test
    void SSAFY_계정이_없는_사용자는_getBalances가_400이다() {
        long noMember = user("01077776666", false);
        long account = account(noMember, "7777", "CONNECTED", true, null);

        assertThatThrownBy(() -> balanceService.getBalances(noMember, List.of(account), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void refresh가_실패해도_이전_캐시는_남는다() {
        balanceService.getBalances(owner, List.of(first), false);
        FinApiException failure = FinApiFailures.timedOut("inquireDemandDepositAccountBalance");
        balances.put("6547", failure);

        assertThatThrownBy(() -> balanceService.getBalances(owner, List.of(first), true)).isSameAs(failure);
        assertThat(balanceService.getBalances(owner, List.of(first), false))
                .singleElement().extracting(AccountBalance::balance).isEqualTo(620000L);
    }

    @Test
    void 더_나중에_읽은_캐시는_늦게_끝난_조회가_덮지_않는다() {
        // 다른 refresh가 이미 "미래" 시각의 값을 써 둔 상태. 지금 읽은 값은 응답에는 쓰지만 캐시는 바꾸지 않는다.
        long later = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1).toEpochSecond(ZoneOffset.UTC);
        redis.opsForValue().set("account:balance:" + first, "9999|" + later, java.time.Duration.ofMinutes(10));

        assertThat(balanceService.getBalances(owner, List.of(first), true))
                .singleElement().extracting(AccountBalance::balance).isEqualTo(620000L);
        assertThat(redis.opsForValue().get("account:balance:" + first)).startsWith("9999|");
    }

    @Test
    void refresh는_사용자별_1분에_6번까지이고_넘으면_429다() {
        for (int i = 0; i < 6; i++) {
            balanceService.getBalances(owner, List.of(first), true);
        }

        assertThatThrownBy(() -> balanceService.getBalances(owner, List.of(first), true))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(e.getDetails()).containsKey("retryAfterSeconds");
                });
        // 캐시를 쓰는 조회는 한도와 상관없다.
        assertThat(balanceService.getBalances(owner, List.of(first), false)).hasSize(1);
    }

    @Test
    void evict하면_다음_조회는_금융망에서_다시_읽는다() {
        balanceService.getBalances(owner, List.of(first), false);
        balances.put("6547", 10L);

        balanceService.evict(first);

        assertThat(balanceService.getBalances(owner, List.of(first), false))
                .singleElement().extracting(AccountBalance::balance).isEqualTo(10L);
    }

    // ───────── 체크카드 상세의 연결 계좌 잔액 ─────────

    @Test
    void 체크카드_상세는_계좌_잔액_캐시에_있을_때만_연결_계좌_잔액을_보여_준다() throws Exception {
        long check = card(product("SHINHAN", "신한 카페 체크", "CHECK"), "9410123456785678", "ACTIVE", first, null);

        get("/api/cards/" + check)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedAccount.accountId").value(first))
                .andExpect(jsonPath("$.data.linkedAccount.balance").doesNotExist());
        verify(finApiClient, never()).post(eq(SsafyAccountApi.BALANCE_PATH), any(), any(), eq(Balance.class));

        get("/api/accounts").andExpect(status().isOk());
        get("/api/cards/" + check)
                .andExpect(jsonPath("$.data.linkedAccount.balance").value(620000))
                .andExpect(jsonPath("$.data.linkedAccount.balanceAsOf").exists());
    }

    // ───────── 도우미 ─────────

    private int concurrentPatch(CountDownLatch start, long accountId) throws Exception {
        start.await();
        return patch(accountId, "{\"isPrimary\":true}").andReturn().getResponse().getStatus();
    }

    private List<Long> primaries() {
        return jdbc.queryForList("SELECT linked_account_id FROM linked_account WHERE user_id = ? AND is_primary = 1",
                Long.class, owner);
    }

    private long user(String phone, boolean ssafyMember) {
        Map<String, Object> values = new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(phone),
                "identity_verified_at", now(),
                "pin_hash", "$2a$10$test",
                "pin_updated_at", now(),
                "status", "ACTIVE",
                "created_at", now(),
                "updated_at", now()));
        if (ssafyMember) {
            values.put("ssafy_member_id", "y" + phone.substring(2) + "@yamu.app");
            values.put("ssafy_user_key", cipher.encrypt(USER_KEY, CipherField.APP_USER_SSAFY_USER_KEY));
        }
        return insert("app_user", "user_id", values);
    }

    private long account(long userId, String last4, String status, boolean primary, String alias) {
        String accountNo = "088154114975" + last4;
        Map<String, Object> values = new HashMap<>(Map.of("user_id", userId, "bank_code", "088",
                "account_no_enc", cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO),
                "account_no_hash", lookupHasher.account("088", accountNo), "account_last4", last4,
                "status", status, "is_primary", primary,
                "connected_at", now(), "created_at", now(), "updated_at", now()));
        if (alias != null) {
            values.put("account_alias", alias);
        }
        if ("DISCONNECTED".equals(status)) {
            values.put("disconnected_at", now());
        }
        return insert("linked_account", "linked_account_id", values);
    }

    private long product(String issuer, String name, String type) {
        Map<String, Object> values = new HashMap<>(Map.of("issuer_code", issuer, "product_name", name,
                "card_type", type, "created_at", now(), "updated_at", now()));
        if ("CREDIT".equals(type)) {
            values.put("billing_cutoff_days", 13);
        }
        return insert("card_product", "card_product_id", values);
    }

    private long card(long product, String cardNo, String status, long linkedAccount, String alias) {
        Map<String, Object> values = new HashMap<>(Map.of("user_id", owner, "card_product_id", product,
                "card_no_enc", cipher.encrypt(cardNo, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(cardNo), "card_last4", cardNo.substring(12),
                "status", status, "linked_account_id", linkedAccount,
                "registered_at", now(), "created_at", now(), "updated_at", now()));
        if (alias != null) {
            values.put("card_alias", alias);
        }
        return insert("user_card", "user_card_id", values);
    }

    private long bill(long card, long accountId, String status, LocalDate withdrawalDate) {
        YearMonth month = YearMonth.from(withdrawalDate);
        return insert("card_bill", "card_bill_id", new HashMap<>(Map.ofEntries(
                Map.entry("user_id", owner), Map.entry("user_card_id", card),
                Map.entry("linked_account_id", accountId), Map.entry("billing_month", Date.valueOf(month.atDay(1))),
                Map.entry("period_start", Date.valueOf(month.minusMonths(1).atDay(2))),
                Map.entry("period_end", Date.valueOf(month.atDay(1))),
                Map.entry("withdrawal_date", Date.valueOf(withdrawalDate)), Map.entry("status", status),
                Map.entry("confirmed_amount", 128500L), Map.entry("confirmed_at", now()),
                Map.entry("created_at", now()), Map.entry("updated_at", now()))));
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private ResultActions get(String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path).with(AuthUsers.asUser(owner, deviceId)));
    }

    private ResultActions patch(long accountId, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.patch("/api/accounts/" + accountId)
                .with(AuthUsers.asUser(owner, deviceId))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static ResultMatcher errorCode(int status, String code) {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code")).isEqualTo(code);
        };
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
    }
}
