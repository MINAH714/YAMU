package com.yamu.be.global.finapi;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 기관거래고유번호(Header.institutionTransactionUniqueNo, DB fin_request_no) 채번.
 * 스키마 사전 「금융망 요청 번호 채번 규칙」대로 {@code YYYYMMDD + HHMMSS + 그 초의 일련번호 6자리} = 20자리 숫자를 만든다.
 *
 * <pre>
 * fin:txno:{yyyyMMddHHmmss} → 그 초에 발급한 수   TTL 1분
 * 예: 2026-10-27 14:30:05(한국)의 첫 번호 → 20261027143005000001
 * </pre>
 *
 * 날짜·시각은 한국 시각이다. {@link FinApiClient}는 Header의 transmissionDate·transmissionTime을 이 번호의 앞 14자리로 채워 둘을 맞춘다.
 * 서버가 여러 대여도 Redis INCR 하나를 같이 쓰므로 번호가 겹치지 않는다. TTL을 1초가 아니라 1분으로 둔 것은
 * 서버 시계가 몇 초 어긋나도 이미 지나간 초의 카운터가 지워져 1부터 다시 세는 일을 막기 위해서다.
 *
 * <p>한 초에 999,999개를 넘거나 Redis에 닿지 않으면 번호를 재사용하지 않고 금융망에 보내기 전에 실패한다(NOT_SENT).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinTransactionNoIssuer {

    private static final String KEY_PREFIX = "fin:txno:";
    private static final long MAX_SEQUENCE = 999_999;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String TTL_MILLIS = "60000";

    /** 1을 더하고, 처음 만든 키면 TTL을 건다. INCR과 PEXPIRE를 한 번에 해서 TTL 없는 키가 남지 않게 한다. */
    private static final RedisScript<Long> NEXT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    /** 새 번호. 실패하면 {@link FinApiException}(NOT_SENT, apiName 없음). */
    public String issue() {
        String second = ZonedDateTime.now(clock.withZone(KST)).format(SECOND);
        Long sequence;
        try {
            sequence = redis.execute(NEXT, List.of(KEY_PREFIX + second), TTL_MILLIS);
        } catch (DataAccessException e) {
            log.warn("기관거래고유번호 채번 실패({}) → 금융망 요청 보류", e.getClass().getSimpleName());
            throw FinApiException.notSent(null);
        }
        if (sequence == null || sequence < 1 || sequence > MAX_SEQUENCE) {
            log.warn("기관거래고유번호 일련번호 초과 second={} seq={} → 금융망 요청 보류", second, sequence);
            throw FinApiException.notSent(null);
        }
        return second + String.format("%06d", sequence);
    }
}
