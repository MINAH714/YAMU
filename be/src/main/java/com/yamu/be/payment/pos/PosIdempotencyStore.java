package com.yamu.be.payment.pos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * POS 결제 요청의 Idempotency-Key 결과를 Redis에 기억한다(명세 1장: 24시간).
 *
 * <pre>
 * pos:payreq:idem:{posCode}:{key}      → JSON { fingerprint, paymentId }
 * pos:payreq:idem:{posCode}:{key}:lock → 처리 중 표시(RedisLocks)
 * </pre>
 *
 * 키는 POS 시나리오별로 센다. DB의 request_key UNIQUE가 최종 안전장치다.
 */
@Component
public class PosIdempotencyStore {

    static final String KEY_PREFIX = "pos:payreq:idem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PosProperties properties;

    public PosIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper, PosProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<Previous> find(String posCode, String idempotencyKey) {
        String json = redis.opsForValue().get(key(posCode, idempotencyKey));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Previous.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void remember(String posCode, String idempotencyKey, Previous previous) {
        try {
            redis.opsForValue().set(key(posCode, idempotencyKey), objectMapper.writeValueAsString(previous),
                    properties.idempotencyTtl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String lockKey(String posCode, String idempotencyKey) {
        return key(posCode, idempotencyKey) + ":lock";
    }

    private static String key(String posCode, String idempotencyKey) {
        return KEY_PREFIX + posCode + ":" + idempotencyKey;
    }

    /**
     * 처음 처리한 결과.
     *
     * @param fingerprint 최초 요청 불변 내용의 SHA-256(16진수). 같은 키에 다른 본문이 오면 409
     */
    public record Previous(String fingerprint, Long paymentId) {}
}
