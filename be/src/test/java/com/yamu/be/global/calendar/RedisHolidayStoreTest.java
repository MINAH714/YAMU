package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.RedisTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisHolidayStoreTest extends RedisTestSupport {

    static final Instant SYNCED_AT = Instant.parse("2026-11-02T19:00:00Z"); // 한국 11-03 04:00

    @Autowired RedisHolidayStore store;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void 저장한_목록과_동기화_시각을_읽는다() {
        store.replace(2026, Map.of(LocalDate.of(2026, 5, 5), "어린이날", LocalDate.of(2026, 5, 1), "노동절"), SYNCED_AT);

        assertThat(store.find(2026)).hasValueSatisfying(found -> assertThat(found).containsExactly(
                Map.entry(LocalDate.of(2026, 5, 1), "노동절"),
                Map.entry(LocalDate.of(2026, 5, 5), "어린이날")));
        assertThat(store.syncedAt(2026)).contains(SYNCED_AT);
    }

    @Test
    void 키_구조는_holiday_연도_해시와_synced_at_문자열이다() {
        store.replace(2026, Map.of(LocalDate.of(2026, 5, 5), "어린이날"), SYNCED_AT);

        assertThat(redis.opsForHash().entries("holiday:2026")).containsExactly(Map.entry("2026-05-05", "어린이날"));
        assertThat(redis.opsForValue().get("holiday:2026:synced-at")).isEqualTo("2026-11-02T19:00:00Z");
    }

    @Test
    void 다시_저장하면_그_해를_통째로_바꾸고_임시_키를_남기지_않는다() {
        store.replace(2026, Map.of(LocalDate.of(2026, 5, 5), "어린이날", LocalDate.of(2026, 10, 2), "임시공휴일"), SYNCED_AT);

        store.replace(2026, Map.of(LocalDate.of(2026, 5, 5), "어린이날"), SYNCED_AT.plusSeconds(86_400));

        assertThat(store.find(2026)).hasValueSatisfying(found -> assertThat(found).containsOnlyKeys(LocalDate.of(2026, 5, 5)));
        assertThat(store.syncedAt(2026)).contains(SYNCED_AT.plusSeconds(86_400));
        assertThat(redis.hasKey("holiday:2026:staging")).isFalse();
    }

    @Test
    void 동기화한_적_없는_해는_비어_있다() {
        assertThat(store.find(2027)).isEmpty();
        assertThat(store.syncedAt(2027)).isEmpty();
    }

    @Test
    void 빈_목록은_저장하지_않는다() {
        assertThatThrownBy(() -> store.replace(2028, Map.of(), SYNCED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(2028)).isEmpty();
    }
}
