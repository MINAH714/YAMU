package com.yamu.be.global.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 공휴일 API로 받은 목록을 연 단위로 보관한다. 서버를 다시 띄워도 API를 다시 부르지 않고 이 값을 읽는다.
 *
 * <pre>
 * holiday:{연도}            해시  {"2026-05-05": "어린이날", ...}
 * holiday:{연도}:synced-at  문자열 마지막으로 성공한 동기화 시각(UTC, ISO-8601)
 * </pre>
 *
 * 만료 시간은 두지 않는다. 동기화가 계속 실패해도 마지막으로 받은 목록을 쓰는 편이 내장 파일만 쓰는 것보다 낫다.
 * 덮어쓸 때는 임시 키에 다 쓴 뒤 RENAME으로 바꿔서, 반만 쓴 목록을 읽는 순간이 없게 한다.
 */
public class RedisHolidayStore {

    static final String KEY_PREFIX = "holiday:";

    private final StringRedisTemplate redis;

    public RedisHolidayStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 그 해 목록을 통째로 바꾼다. 빈 목록은 받지 않는다(빈 해는 저장하지 않고 "없음"으로 둔다). */
    public void replace(int year, Map<LocalDate, String> holidays, Instant syncedAt) {
        if (holidays.isEmpty()) {
            throw new IllegalArgumentException("빈 공휴일 목록은 저장하지 않습니다: " + year);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        holidays.forEach((date, name) -> fields.put(date.toString(), name));

        String staging = key(year) + ":staging";
        redis.delete(staging);
        redis.opsForHash().putAll(staging, fields);
        redis.rename(staging, key(year));
        redis.opsForValue().set(syncedAtKey(year), syncedAt.toString());
    }

    /** 그 해 목록. 동기화된 적 없으면 비어 있다. */
    public Optional<Map<LocalDate, String>> find(int year) {
        Map<Object, Object> fields = redis.opsForHash().entries(key(year));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        Map<LocalDate, String> holidays = new TreeMap<>();
        fields.forEach((date, name) -> holidays.put(LocalDate.parse((String) date), (String) name));
        return Optional.of(holidays);
    }

    /** 마지막으로 성공한 동기화 시각. 없으면 비어 있다. */
    public Optional<Instant> syncedAt(int year) {
        return Optional.ofNullable(redis.opsForValue().get(syncedAtKey(year))).map(Instant::parse);
    }

    static String key(int year) {
        return KEY_PREFIX + year;
    }

    private static String syncedAtKey(int year) {
        return key(year) + ":synced-at";
    }
}
