package com.yamu.be.payment.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제 승인의 Idempotency-Key 결과를 Redis에 기억한다(명세 1장: 24시간, 같은 키는 처음 응답을 그대로).
 *
 * <pre>
 * payment:approve:idem:{userId}:{key}      → JSON { fingerprint, paymentId, response }
 * payment:approve:idem:{userId}:{key}:lock → 처리 중 표시(RedisLocks)
 * </pre>
 *
 * 키는 사용자별로 센다. 같은 키를 다른 결제에 다시 쓰면 지문이 달라 409가 된다.
 * 임시: 보관 시간이 지나 사라진 뒤 같은 키가 다시 오면 새 요청으로 보고, 결제 상태 검사가 409·410으로 답한다(명세 "DB 상태로 다시 만듦"의 최소 해석).
 */
@Component
public class ApprovalIdempotencyStore {

    static final String KEY_PREFIX = "payment:approve:idem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PaymentApprovalProperties properties;

    public ApprovalIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper,
            PaymentApprovalProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<Previous> find(Long userId, String idempotencyKey) {
        String json = redis.opsForValue().get(key(userId, idempotencyKey));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Previous.class));
        } catch (JsonProcessingException e) {
            // 보관 중 응답 모양이 바뀐 것(배포 직후). 없는 것으로 본다
            return Optional.empty();
        }
    }

    public void remember(Long userId, String idempotencyKey, Previous previous) {
        try {
            redis.opsForValue().set(key(userId, idempotencyKey), objectMapper.writeValueAsString(previous),
                    properties.idempotencyTtl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String lockKey(Long userId, String idempotencyKey) {
        return key(userId, idempotencyKey) + ":lock";
    }

    private static String key(Long userId, String idempotencyKey) {
        return KEY_PREFIX + userId + ":" + idempotencyKey;
    }

    /**
     * 처음 처리한 결과.
     *
     * @param fingerprint 요청 내용(결제 번호·고른 카드). 같은 키에 다른 내용이 오면 409
     * @param response    처음 돌려준 응답. 재전송에 그대로 돌려준다
     */
    public record Previous(String fingerprint, Long paymentId, PaymentApprovalResponse response) {}
}
