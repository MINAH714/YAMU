package com.yamu.be.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.auth.LoginRateLimiter;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 잔액 조회의 동시 호출·전체 기한·캐시 장애 처리를 금융망·Redis 없이 확인한다. 실제 Redis 동작은 AccountViewIntegrationTest.
 */
class AccountBalanceServiceTest {

    private static final long USER = 1L;
    private static final byte[] KEY_ENC = {1};

    LinkedAccountRepository accounts = mock(LinkedAccountRepository.class);
    AppUserRepository users = mock(AppUserRepository.class);
    SsafyAccountApi ssafy = mock(SsafyAccountApi.class);
    PersonalDataCipher cipher = mock(PersonalDataCipher.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    AccountBalanceService service;

    LinkedAccount fast = account(10L, "fast");
    LinkedAccount slow = account(11L, "slow");

    @BeforeEach
    void setUp() {
        AppUser user = mock(AppUser.class);
        when(user.getSsafyUserKey()).thenReturn(KEY_ENC);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        when(cipher.decrypt(KEY_ENC, CipherField.APP_USER_SSAFY_USER_KEY)).thenReturn("user-key");
        when(accounts.findByIdInAndUserIdAndStatus(anyList(), eq(USER), eq(LinkedAccountStatus.CONNECTED)))
                .thenReturn(List.of(fast, slow));
        // Redis가 죽어 있다: 읽기·쓰기 모두 연결 실패
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(cipher.decrypt(any(byte[].class), eq(CipherField.LINKED_ACCOUNT_NO)))
                .thenAnswer(inv -> new String((byte[]) inv.getArgument(0)));
        service = new AccountBalanceService(accounts, users, ssafy, cipher, redis, rateLimiter,
                new AccountBalanceProperties(Duration.ofMinutes(10), 5, Duration.ofMillis(800), 6),
                Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    @Test
    void Redis가_죽어도_금융망에서_읽어_잔액을_돌려준다() {
        when(ssafy.balance("user-key", "fast")).thenReturn(1000L);
        when(ssafy.balance("user-key", "slow")).thenReturn(2000L);

        assertThat(service.getBalances(USER, List.of(10L, 11L), false))
                .extracting(AccountBalance::balance).containsExactly(1000L, 2000L);
        assertThat(service.findCachedBalance(USER, 10L)).isEmpty();
    }

    @Test
    void 추천용_부분_결과는_실패와_확인된_0원을_구분하고_정상_계좌를_보존한다() {
        when(ssafy.balance("user-key", "fast")).thenReturn(0L);
        when(ssafy.balance("user-key", "slow")).thenThrow(FinApiException.timedOut("balance"));
        var results=service.getBalanceResults(USER,List.of(10L,11L,10L));
        assertThat(results).hasSize(2);
        assertThat(results.get(10L).balance().balance()).isZero();
        assertThat(results.get(10L).errorCode()).isNull();
        assertThat(results.get(11L).balance()).isNull();
        assertThat(results.get(11L).errorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
        org.mockito.Mockito.verify(ssafy,org.mockito.Mockito.times(1)).balance("user-key","fast");
        org.mockito.Mockito.verify(ssafy,org.mockito.Mockito.times(1)).balance("user-key","slow");
    }

    @Test
    void 추천용_부분_조회는_잘못된_개별_응답도_다른_정상_계좌로_전파하지_않는다() {
        when(ssafy.balance("user-key","fast")).thenReturn(10_000L);
        when(ssafy.balance("user-key","slow")).thenThrow(new IllegalArgumentException("invalid response"));
        var results=service.getBalanceResults(USER,List.of(10L,11L));
        assertThat(results.get(10L).balance().balance()).isEqualTo(10_000);
        assertThat(results.get(11L).balance()).isNull();
        assertThat(results.get(11L).errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 부분_조회에서는_없는_계좌만_거절하고_정상_계좌는_조회한다() {
        when(accounts.findByIdInAndUserIdAndStatus(anyList(),eq(USER),eq(LinkedAccountStatus.CONNECTED))).thenReturn(List.of(fast));
        when(ssafy.balance("user-key","fast")).thenReturn(10_000L);
        var result=service.getBalanceResults(USER,List.of(10L,99L));
        assertThat(result.get(10L).balance().balance()).isEqualTo(10_000);
        assertThat(result.get(99L).balance()).isNull();
        assertThat(result.get(99L).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        org.mockito.Mockito.verify(ssafy,org.mockito.Mockito.times(1)).balance(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 계좌들을_동시에_조회해_계좌_수만큼_기다리지_않는다() {
        when(ssafy.balance(eq("user-key"), anyString())).thenAnswer(inv -> {
            Thread.sleep(400);
            return 1000L;
        });

        long started = System.nanoTime();
        service.getBalances(USER, List.of(10L, 11L), false);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // 차례로 부르면 800ms(기한)를 넘는다. 동시에 부르면 400ms 남짓.
        assertThat(elapsedMs).isLessThan(750);
    }

    @Test
    void 전체_기한을_넘기면_getBalances는_504_결과_불명이고_목록은_그_계좌만_뺀다() {
        when(ssafy.balance("user-key", "fast")).thenReturn(1000L);
        when(ssafy.balance("user-key", "slow")).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return 2000L;
        });

        assertThatThrownBy(() -> service.getBalances(USER, List.of(10L, 11L), false))
                .isInstanceOfSatisfying(FinApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
                    assertThat(e.getOutcome()).isEqualTo(FinApiOutcome.UNKNOWN);
                });

        Map<Long, AccountBalance> listed = service.readAvailable(USER, List.of(fast, slow), false);
        assertThat(listed).containsOnlyKeys(10L);
    }

    @Test
    void evict는_Redis가_죽어도_예외를_던지지_않는다() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        service.evict(10L);
        service.evict(null);
    }

    private static LinkedAccount account(long id, String accountNo) {
        LinkedAccount account = mock(LinkedAccount.class);
        byte[] enc = accountNo.getBytes();
        when(account.getId()).thenReturn(id);
        when(account.getAccountNoEnc()).thenReturn(enc);
        when(account.isConnected()).thenReturn(true);
        return account;
    }
}
