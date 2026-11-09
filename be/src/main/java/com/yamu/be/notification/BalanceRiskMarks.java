package com.yamu.be.notification;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 잔고 위험 작업이 Redis에 남기는 표시 두 가지.
 *
 * <ul>
 *   <li>오늘 판정함: 매분 도는 작업이 같은 사용자를 하루에 한 번만 판정하게 한다. 받는 시각이 지났는데 아직 판정하지 않은 사용자를 고르므로,
 *       서버가 그 시각에 꺼져 있었거나 앞 실행이 길어 1분을 건너뛰어도 다음 실행이 따라잡는다. 2일 뒤 사라진다.</li>
 *   <li>위험이 이어지는 중: 마지막으로 위험을 본 한국 날짜. 오늘부터 이미 안전 잔액 아래인 구간은 첫날이 매일 "오늘"로 바뀌어 키가 달라지므로,
 *       어제도 위험이었으면 같은 구간이 이어지는 것으로 보고 다시 알리지 않는다. 3일 뒤 사라진다.</li>
 * </ul>
 */
@Component
class BalanceRiskMarks {

    private static final String CHECKED = "notification:balance-risk:checked:";
    private static final String ACTIVE = "notification:balance-risk:active:";
    private static final Duration CHECKED_TTL = Duration.ofDays(2);
    private static final Duration ACTIVE_TTL = Duration.ofDays(3);

    private final StringRedisTemplate redis;

    BalanceRiskMarks(StringRedisTemplate redis) {
        this.redis = redis;
    }

    boolean checked(long userId, LocalDate today) {
        return Boolean.TRUE.equals(redis.hasKey(CHECKED + today + ":" + userId));
    }

    void markChecked(long userId, LocalDate today) {
        redis.opsForValue().set(CHECKED + today + ":" + userId, "1", CHECKED_TTL);
    }

    /** 어제나 오늘 위험을 봤는지. */
    boolean riskSeenSince(long userId, LocalDate yesterday) {
        String seen = redis.opsForValue().get(ACTIVE + userId);
        return seen != null && !LocalDate.parse(seen).isBefore(yesterday);
    }

    void rememberRisk(long userId, LocalDate today) {
        redis.opsForValue().set(ACTIVE + userId, today.toString(), ACTIVE_TTL);
    }

    void clearRisk(long userId) {
        redis.delete(ACTIVE + userId);
    }
}
