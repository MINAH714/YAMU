package com.yamu.be.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.auth.LoginRateLimiter;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 홈 동기화(S15P31E103-378)가 쓰는 {@code getBalanceResults(userId, ids, refresh)}를 금융망·Redis 없이 확인한다.
 * refresh=true면 캐시를 건너뛰고 새로 읽어 캐시를 바꾸고, {@code getBalances}의 사용자별 1분 한도는 세지 않는다.
 */
class AccountBalanceRefreshResultsTest {

    private static final long USER = 1L;
    private static final byte[] KEY_ENC = {1};
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    /** 캐시에 들어 있는 3분 전 잔액. */
    private static final String CACHED = "500|" + NOW.minusSeconds(180).getEpochSecond();

    LinkedAccountRepository accounts = mock(LinkedAccountRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    SsafyAccountApi ssafy = mock(SsafyAccountApi.class);
    PersonalDataCipher cipher = mock(PersonalDataCipher.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    AccountBalanceService service;

    LinkedAccount first = account(10L, "first");
    LinkedAccount second = account(11L, "second");

    @BeforeEach
    void setUp() {
        AppUser user = mock(AppUser.class);
        when(user.getSsafyUserKey()).thenReturn(KEY_ENC);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        when(cipher.decrypt(KEY_ENC, CipherField.APP_USER_SSAFY_USER_KEY)).thenReturn("user-key");
        when(cipher.decrypt(any(byte[].class), eq(CipherField.LINKED_ACCOUNT_NO)))
                .thenAnswer(inv -> new String((byte[]) inv.getArgument(0)));
        when(accounts.findByIdInAndUserIdAndStatus(anyList(), eq(USER), eq(LinkedAccountStatus.CONNECTED)))
                .thenReturn(List.of(first, second));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(CACHED);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        service = new AccountBalanceService(accounts, users, ssafy, cipher, redis, rateLimiter,
                new AccountBalanceProperties(Duration.ofMinutes(10), 5, Duration.ofMillis(800), 6),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    @Test
    void refresh가_false면_캐시를_쓰고_금융망을_부르지_않는다() {
        var results = service.getBalanceResults(USER, List.of(10L, 11L), false);

        assertThat(results.get(10L).balance().balance()).isEqualTo(500L);
        assertThat(results.get(11L).balance().balance()).isEqualTo(500L);
        verify(ssafy, never()).balance(anyString(), anyString());
    }

    @Test
    void refresh가_true면_캐시를_건너뛰고_새로_읽어_캐시를_바꾸고_1분_한도는_세지_않는다() {
        when(ssafy.balance("user-key", "first")).thenReturn(1_000L);
        when(ssafy.balance("user-key", "second")).thenReturn(2_000L);

        var results = service.getBalanceResults(USER, List.of(10L, 11L), true);

        assertThat(results.get(10L).balance().balance()).isEqualTo(1_000L);
        assertThat(results.get(10L).balance().fetchedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC).toLocalDateTime());
        assertThat(results.get(11L).balance().balance()).isEqualTo(2_000L);
        verify(values, never()).get(anyString());
        verify(redis).execute(any(RedisScript.class), eq(List.of("account:balance:10")),
                eq("1000|" + NOW.getEpochSecond()), any(), any());
        verify(redis).execute(any(RedisScript.class), eq(List.of("account:balance:11")),
                eq("2000|" + NOW.getEpochSecond()), any(), any());
        verify(rateLimiter, never()).checkBalanceRefresh(any(), anyInt());
    }

    @Test
    void refresh가_true여도_실패한_계좌만_코드로_나누고_나머지는_돌려준다() {
        when(ssafy.balance("user-key", "first")).thenReturn(1_000L);
        when(ssafy.balance("user-key", "second")).thenThrow(FinApiException.timedOut("balance"));

        var results = service.getBalanceResults(USER, List.of(10L, 11L), true);

        assertThat(results.get(10L).balance().balance()).isEqualTo(1_000L);
        assertThat(results.get(10L).errorCode()).isNull();
        assertThat(results.get(11L).balance()).isNull();
        assertThat(results.get(11L).errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
    }

    private static LinkedAccount account(Long id, String accountNo) {
        LinkedAccount account = mock(LinkedAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getAccountNoEnc()).thenReturn(accountNo.getBytes());
        return account;
    }
}
