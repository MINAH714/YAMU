package com.yamu.be.home;

import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 금융 데이터 동기화({@code POST /api/sync}, 2026-11-10 결정).
 *
 * <ul>
 *   <li>연결(CONNECTED) 계좌의 잔액을 캐시를 건너뛰고 금융망에서 새로 읽어 잔액 캐시를 바꾼다. 서버는 금융망 거래내역을
 *       저장하지 않아 거래는 받아오지 않는다({@code newTransactions}는 0)</li>
 *   <li>계좌 하나가 실패해도 나머지는 읽고, 실패한 계좌는 {@code failed}에 코드와 함께 적는다. 응답은 200이다</li>
 *   <li>연타: 같은 사용자가 {@value #REUSE_SECONDS}초 안에 다시 부르면 금융망을 부르지 않고 지난 결과를 그대로 준다(앱과 같은 규칙).
 *       동시에 두 번 오면 Redis 잠금으로 하나만 금융망을 부르고, 다른 하나는 지난 결과를 주거나 지난 결과도 없으면 429다</li>
 *   <li>Redis가 죽으면 연타 막기 없이 동기화한다(잔액 조회가 Redis 없이도 되는 것과 같다). 경고 로그만 남긴다</li>
 * </ul>
 *
 * <p>금융망 호출은 DB 트랜잭션 밖에서 한다. 이 클래스에는 {@code @Transactional}을 붙이지 않는다.
 */
@Slf4j
@Service
public class SyncService {

    static final int REUSE_SECONDS = 30;
    static final Duration REUSE = Duration.ofSeconds(REUSE_SECONDS);
    /** 잠금 유지 시간. 잔액 조회 전체 기한(10초)보다 길게 두고, 서버가 잠금을 못 풀고 죽어도 이 시간 뒤엔 풀린다. */
    static final Duration LOCK_TTL = Duration.ofSeconds(20);

    private static final String LAST_PREFIX = "home:sync:last:";
    private static final String LOCK_PREFIX = "home:sync:lock:";
    /** 내가 건 잠금일 때만 푼다. 잠금이 시간 초과로 풀린 뒤 다른 요청이 건 잠금을 지우지 않게. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final LinkedAccountRepository accounts;
    private final AccountBalanceService balances;
    private final StringRedisTemplate redis;
    private final Clock clock;

    SyncService(LinkedAccountRepository accounts, AccountBalanceService balances, StringRedisTemplate redis,
            Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.redis = redis;
        this.clock = clock;
    }

    public SyncResponse sync(long userId) {
        Optional<SyncResponse> last = last(userId);
        if (last.isPresent()) {
            return last.get();
        }
        String token = UUID.randomUUID().toString();
        Boolean locked = tryLock(userId, token);
        if (Boolean.FALSE.equals(locked)) {
            // 다른 요청이 지금 동기화 중이다. 그쪽이 막 끝났으면 그 결과를 준다.
            return last(userId).orElseThrow(() -> new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    Map.of("retryAfterSeconds", lockRemainingSeconds(userId))));
        }
        try {
            SyncResponse result = refresh(userId);
            store(userId, result);
            return result;
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                release(userId, token);
            }
        }
    }

    private SyncResponse refresh(long userId) {
        LocalDateTime syncedAt = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS);
        List<Long> ids = accounts.findByUserIdAndStatusOrderByIdAsc(userId, LinkedAccountStatus.CONNECTED).stream()
                .map(LinkedAccount::getId)
                .toList();
        if (ids.isEmpty()) {
            return new SyncResponse(syncedAt, 0, List.of());
        }
        Map<Long, AccountBalanceService.BalanceRead> results = balances.getBalanceResults(userId, ids, true);
        List<SyncResponse.Failure> failed = new ArrayList<>();
        for (Long id : ids) {
            AccountBalanceService.BalanceRead read = results.get(id);
            if (read == null || read.errorCode() != null) {
                String code = read == null ? ErrorCode.INTERNAL_ERROR.name() : read.errorCode().name();
                log.warn("동기화 잔액 조회 실패 accountId={} code={}", id, code);
                failed.add(new SyncResponse.Failure(id, code));
            }
        }
        return new SyncResponse(syncedAt, 0, List.copyOf(failed));
    }

    /** 30초 안의 지난 결과. Redis 장애·깨진 값은 없는 것으로 본다. */
    private Optional<SyncResponse> last(long userId) {
        String value;
        try {
            value = redis.opsForValue().get(LAST_PREFIX + userId);
        } catch (RuntimeException e) {
            log.warn("동기화 지난 결과 읽기 실패({}) userId={}", e.getClass().getSimpleName(), userId);
            return Optional.empty();
        }
        return value == null ? Optional.empty() : decode(value);
    }

    private void store(long userId, SyncResponse result) {
        try {
            redis.opsForValue().set(LAST_PREFIX + userId, encode(result), REUSE);
        } catch (RuntimeException e) {
            log.warn("동기화 결과 저장 실패({}) userId={} → 다음 호출은 금융망을 다시 부름", e.getClass().getSimpleName(), userId);
        }
    }

    /** 잠금을 걸면 TRUE, 다른 요청이 잡고 있으면 FALSE, Redis 장애면 null(잠금 없이 진행). */
    private Boolean tryLock(long userId, String token) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_PREFIX + userId, token, LOCK_TTL));
        } catch (RuntimeException e) {
            log.warn("동기화 잠금 실패({}) userId={} → 연타 막기 없이 동기화", e.getClass().getSimpleName(), userId);
            return null;
        }
    }

    private void release(long userId, String token) {
        try {
            redis.execute(RELEASE, List.of(LOCK_PREFIX + userId), token);
        } catch (RuntimeException e) {
            log.warn("동기화 잠금 해제 실패({}) userId={} → {}초 뒤 풀림", e.getClass().getSimpleName(), userId,
                    LOCK_TTL.toSeconds());
        }
    }

    private long lockRemainingSeconds(long userId) {
        try {
            Long seconds = redis.getExpire(LOCK_PREFIX + userId, TimeUnit.SECONDS);
            return seconds == null || seconds < 1 ? 1 : seconds;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** "동기화 epoch 초|계좌:코드,계좌:코드". 실패가 없으면 "|" 뒤가 비어 있다. */
    static String encode(SyncResponse result) {
        StringBuilder value = new StringBuilder()
                .append(result.syncedAt().toEpochSecond(ZoneOffset.UTC)).append('|');
        for (int i = 0; i < result.failed().size(); i++) {
            SyncResponse.Failure failure = result.failed().get(i);
            if (i > 0) {
                value.append(',');
            }
            value.append(failure.accountId()).append(':').append(failure.code());
        }
        return value.toString();
    }

    static Optional<SyncResponse> decode(String value) {
        try {
            int bar = value.indexOf('|');
            LocalDateTime syncedAt = LocalDateTime.ofEpochSecond(Long.parseLong(value.substring(0, bar)), 0,
                    ZoneOffset.UTC);
            List<SyncResponse.Failure> failed = new ArrayList<>();
            String rest = value.substring(bar + 1);
            if (!rest.isEmpty()) {
                for (String part : rest.split(",")) {
                    int colon = part.indexOf(':');
                    failed.add(new SyncResponse.Failure(Long.parseLong(part.substring(0, colon)),
                            part.substring(colon + 1)));
                }
            }
            return Optional.of(new SyncResponse(syncedAt, 0, List.copyOf(failed)));
        } catch (RuntimeException e) {
            log.warn("동기화 지난 결과 값이 깨짐({}) → 새로 동기화", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
