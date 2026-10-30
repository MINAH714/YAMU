package com.yamu.be.account;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.auth.LoginRateLimiter;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 연결 계좌 잔액. SSAFY {@code inquireDemandDepositAccountBalance}를 계좌마다 부르고, 계좌별로 10분 캐시한다.
 * 계좌 목록 화면과 잔고예보(BE 4)가 같은 캐시를 써서 두 화면의 잔액이 같고 금융망 호출도 한 번으로 끝난다.
 *
 * <ul>
 *   <li>캐시에 없는 계좌는 금융망에 <b>동시에</b> 묻고(최대 {@code maxParallel}개), 조회 전체에 기한({@code overallTimeout})을 둔다.
 *       금융망이 느려도 계좌 수 × 10초씩 기다리지 않는다</li>
 *   <li>캐시(Redis)가 죽어도 잔액 조회는 실패하지 않는다. 읽기·쓰기 실패는 경고만 남기고 캐시 없이 금융망에서 읽는다</li>
 *   <li>출금처럼 잔액을 바꾼 쪽은 {@link #evict(Long)}로 캐시를 지운다(BE 2 청구 출금 등). 지우지 않으면 최대 10분 동안
 *       출금 전 잔액이 보인다</li>
 * </ul>
 *
 * <p>금융망 호출은 DB 트랜잭션 밖에서 한다. 이 클래스에는 {@code @Transactional}을 붙이지 않는다.
 */
@Slf4j
@Service
public class AccountBalanceService implements DisposableBean {

    private static final String CACHE_PREFIX = "account:balance:";

    /**
     * 더 새로 읽은 값일 때만 캐시를 바꾼다. refresh 두 개가 겹쳐 늦게 끝난 쪽이 더 오래된 잔액으로 덮는 것을 막는다.
     * 값은 "잔액|조회 시각(epoch 초)".
     */
    private static final RedisScript<Long> PUT_IF_NEWER = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
                local at = tonumber(string.match(current, '|(%d+)$'))
                if at and at > tonumber(ARGV[2]) then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final LinkedAccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final SsafyAccountApi ssafy;
    private final PersonalDataCipher cipher;
    private final StringRedisTemplate redis;
    private final LoginRateLimiter rateLimiter;
    private final AccountBalanceProperties properties;
    private final Clock clock;
    private final ExecutorService executor;

    public AccountBalanceService(LinkedAccountRepository accountRepository, AppUserRepository userRepository,
            SsafyAccountApi ssafy, PersonalDataCipher cipher, StringRedisTemplate redis, LoginRateLimiter rateLimiter,
            AccountBalanceProperties properties, Clock clock) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ssafy = ssafy;
        this.cipher = cipher;
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(properties.maxParallel(), task -> {
            Thread thread = new Thread(task, "account-balance-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 선택한 계좌들의 잔액. 잔고예보(BE 4)와 맞춘 계약이다(2026-10-29).
     *
     * <ul>
     *   <li>넘긴 순서대로 돌려준다. 같은 번호가 여러 번 오면 하나만 돌려준다. 빈 목록이면 빈 목록</li>
     *   <li>하나라도 없는 번호·남의 계좌·연결 해제한 계좌면 전체를 404 RESOURCE_NOT_FOUND로 거절한다. 금융망을 부르기 전에 확인한다</li>
     *   <li>금융망 실패는 공통 클라이언트 예외(502·504)를 그대로 던진다. 일부 계좌만 성공해도 결과를 돌려주지 않는다.
     *       조회 전체 기한을 넘기면 504 UPSTREAM_TIMEOUT(결과 불명)</li>
     *   <li>refresh=false면 10분 캐시를 쓰고, true면 캐시를 건너뛰고 새로 읽어 캐시를 바꾼다. true는 사용자별 1분 한도가 있다(429)</li>
     *   <li>SSAFY 계정(userKey)이 없는 사용자는 400 INVALID_REQUEST. 시드에서 SSAFY 회원을 빠뜨린 경우라 정상 흐름에서는 없다</li>
     * </ul>
     */
    public List<AccountBalance> getBalances(Long userId, List<Long> linkedAccountIds, boolean refresh) {
        if (linkedAccountIds == null || linkedAccountIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(linkedAccountIds));
        if (userId == null || ids.contains(null)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<Long, LinkedAccount> owned = connected(userId, ids);
        if (owned.size() != ids.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String userKey = userKeyOf(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        if (refresh) {
            rateLimiter.checkBalanceRefresh(userId, properties.refreshPerMinute());
        }
        Map<Long, Result> results = readAll(userKey, ids.stream().map(owned::get).toList(), refresh);
        List<AccountBalance> balances = new ArrayList<>();
        for (Long id : ids) {
            balances.add(results.get(id).orThrow());
        }
        return balances;
    }

    /**
     * 캐시에 있는 잔액만 돌려준다(금융망을 부르지 않는다). DB 트랜잭션 안에서 화면을 만드는 곳(체크카드 상세 등)이
     * 금융망 응답을 기다리며 DB 연결을 잡고 있지 않게 하려고 둔다. 10분 안에 계좌 목록·잔고예보가 읽었으면 그 값이 나온다.
     * 남의 계좌·해제 계좌·캐시 없음·캐시 장애는 empty.
     */
    public Optional<AccountBalance> findCachedBalance(Long userId, Long linkedAccountId) {
        if (userId == null || linkedAccountId == null) {
            return Optional.empty();
        }
        return accountRepository.findByIdAndUserId(linkedAccountId, userId)
                .filter(LinkedAccount::isConnected)
                .flatMap(account -> cached(account.getId()));
    }

    /**
     * 이 계좌의 잔액 캐시를 지운다. 출금·입금처럼 잔액을 바꾼 쪽(BE 2 청구 출금·환급 등)이 금융망 처리 뒤에 부른다.
     * 캐시가 죽어 있어도 예외를 던지지 않는다(다음 조회는 어차피 10분 안에 새 값으로 바뀐다).
     */
    public void evict(Long linkedAccountId) {
        if (linkedAccountId == null) {
            return;
        }
        try {
            redis.delete(cacheKey(linkedAccountId));
        } catch (RuntimeException e) {
            log.warn("계좌 {} 잔액 캐시 삭제 실패({}) → 최대 TTL 동안 예전 잔액이 보일 수 있음", linkedAccountId,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 계좌 목록 화면용. 계좌마다 따로 읽어, 금융망을 못 읽은(실패·기한 초과) 계좌만 결과에서 빠진다
     * (화면은 그 계좌 잔액을 비워 보여 준다). 호출하는 쪽이 이미 소유·연결 상태를 확인한 계좌를 넘긴다.
     */
    Map<Long, AccountBalance> readAvailable(Long userId, Collection<LinkedAccount> accounts, boolean refresh) {
        Map<Long, AccountBalance> balances = new LinkedHashMap<>();
        if (accounts.isEmpty()) {
            return balances;
        }
        Optional<String> userKey = userKeyOf(userId);
        if (userKey.isEmpty()) {
            return balances;
        }
        if (refresh) {
            rateLimiter.checkBalanceRefresh(userId, properties.refreshPerMinute());
        }
        readAll(userKey.get(), List.copyOf(accounts), refresh).forEach((id, result) -> {
            if (result.balance() != null) {
                balances.put(id, result.balance());
            } else {
                log.warn("계좌 {} 잔액 조회 실패({}) → 잔액 없이 응답", id, result.reason());
            }
        });
        return balances;
    }

    /**
     * 캐시에 있으면 캐시에서, 없으면 금융망에 동시에 묻는다. 조회 전체가 기한을 넘기면 끝나지 않은 계좌는
     * 504 UPSTREAM_TIMEOUT(결과 불명)으로 두고, 그 호출은 취소한다.
     */
    private Map<Long, Result> readAll(String userKey, List<LinkedAccount> accounts, boolean refresh) {
        Map<Long, Result> results = new LinkedHashMap<>();
        Map<Long, CompletableFuture<AccountBalance>> pending = new LinkedHashMap<>();
        for (LinkedAccount account : accounts) {
            if (!refresh) {
                Optional<AccountBalance> cached = cached(account.getId());
                if (cached.isPresent()) {
                    results.put(account.getId(), Result.ok(cached.get()));
                    continue;
                }
            }
            pending.put(account.getId(), CompletableFuture.supplyAsync(() -> fetch(userKey, account), executor));
        }
        long deadline = System.nanoTime() + properties.overallTimeout().toNanos();
        for (Map.Entry<Long, CompletableFuture<AccountBalance>> entry : pending.entrySet()) {
            results.put(entry.getKey(), await(entry.getValue(), deadline));
        }
        // 응답 순서는 넘긴 순서대로.
        Map<Long, Result> ordered = new LinkedHashMap<>();
        accounts.forEach(account -> ordered.put(account.getId(), results.get(account.getId())));
        return ordered;
    }

    private Result await(CompletableFuture<AccountBalance> future, long deadline) {
        long remaining = deadline - System.nanoTime();
        try {
            return Result.ok(future.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS));
        } catch (TimeoutException | CancellationException e) {
            future.cancel(true);
            return Result.failed(FinApiException.timedOut("inquireDemandDepositAccountBalance"));
        } catch (ExecutionException | CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BusinessException business) {
                return Result.failed(business);
            }
            throw new IllegalStateException("잔액 조회 중 예상하지 못한 오류", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Result.failed(FinApiException.timedOut("inquireDemandDepositAccountBalance"));
        }
    }

    private AccountBalance fetch(String userKey, LinkedAccount account) {
        long balance = ssafy.balance(userKey, cipher.decrypt(account.getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO));
        AccountBalance fresh = new AccountBalance(account.getId(), balance, now());
        store(fresh);
        return fresh;
    }

    /** 캐시 쓰기 실패는 경고만 남긴다. 금융망에서 읽은 값은 그대로 돌려준다. */
    private void store(AccountBalance fresh) {
        long fetchedEpoch = fresh.fetchedAt().toEpochSecond(ZoneOffset.UTC);
        try {
            redis.execute(PUT_IF_NEWER, List.of(cacheKey(fresh.linkedAccountId())),
                    fresh.balance() + "|" + fetchedEpoch, String.valueOf(fetchedEpoch),
                    String.valueOf(properties.cacheTtl().toMillis()));
        } catch (RuntimeException e) {
            log.warn("계좌 {} 잔액 캐시 쓰기 실패({}) → 캐시 없이 응답", fresh.linkedAccountId(), e.getClass().getSimpleName());
        }
    }

    /** 캐시 값은 "잔액|조회 시각(epoch 초)". 캐시 장애·깨진 값은 없는 것으로 보고 금융망에서 새로 읽는다. */
    private Optional<AccountBalance> cached(Long accountId) {
        String value;
        try {
            value = redis.opsForValue().get(cacheKey(accountId));
        } catch (RuntimeException e) {
            log.warn("계좌 {} 잔액 캐시 읽기 실패({}) → 금융망에서 읽음", accountId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        int bar = value.indexOf('|');
        try {
            long balance = Long.parseLong(value.substring(0, bar));
            long epochSecond = Long.parseLong(value.substring(bar + 1));
            return Optional.of(new AccountBalance(accountId, balance,
                    LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC)));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            log.warn("계좌 {} 잔액 캐시 값이 깨짐({}) → 금융망에서 읽음", accountId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Map<Long, LinkedAccount> connected(Long userId, List<Long> ids) {
        return accountRepository.findByIdInAndUserIdAndStatus(ids, userId, LinkedAccountStatus.CONNECTED).stream()
                .collect(Collectors.toMap(LinkedAccount::getId, Function.identity()));
    }

    /** SSAFY 계정이 없는 사용자(시드에서 SSAFY 회원을 빠뜨린 경우)는 금융망을 부를 수 없다. */
    private Optional<String> userKeyOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getSsafyUserKey)
                .map(key -> cipher.decrypt(key, CipherField.APP_USER_SSAFY_USER_KEY));
    }

    private static String cacheKey(Long accountId) {
        return CACHE_PREFIX + accountId;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /** 계좌 하나의 결과. 성공이면 balance, 실패면 error(금융망 예외 그대로). */
    private record Result(AccountBalance balance, BusinessException error) {

        static Result ok(AccountBalance balance) {
            return new Result(balance, null);
        }

        static Result failed(BusinessException error) {
            return new Result(null, error);
        }

        AccountBalance orThrow() {
            if (error != null) {
                throw error;
            }
            return balance;
        }

        String reason() {
            return error instanceof FinApiException fin ? fin.getOutcome().name() : error.getErrorCode().name();
        }
    }
}
