package com.yamu.be.notification;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 실적 부족 작업이 Redis에 남기는 "오늘 판정함" 표시(Task 9.3.3). 매분 도는 작업이 같은 사용자를 하루에 한 번만 판정하게 한다.
 * 받는 시각이 지났는데 아직 판정하지 않은 사용자를 고르므로, 서버가 그 시각에 꺼져 있었거나 앞 실행이 길어 1분을 건너뛰어도 다음 실행이
 * 따라잡는다. 2일 뒤 사라진다. 잔고 위험의 {@link BalanceRiskMarks}와 키를 나눠, 두 알림의 받는 시각이 달라도 서로 막지 않는다.
 */
@Component
class PerformanceShortfallMarks {

    private static final String CHECKED = "notification:performance-shortfall:checked:";
    private static final Duration CHECKED_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;

    PerformanceShortfallMarks(StringRedisTemplate redis) {
        this.redis = redis;
    }

    boolean checked(long userId, LocalDate today) {
        return Boolean.TRUE.equals(redis.hasKey(CHECKED + today + ":" + userId));
    }

    void markChecked(long userId, LocalDate today) {
        redis.opsForValue().set(CHECKED + today + ":" + userId, "1", CHECKED_TTL);
    }
}
