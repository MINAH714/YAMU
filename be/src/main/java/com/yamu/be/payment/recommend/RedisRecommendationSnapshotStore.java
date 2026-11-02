package com.yamu.be.payment.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 추천 스냅샷을 Redis에 JSON으로 보관한다.
 *
 * <pre>
 * payment:rec:{paymentId} → RecommendationResult JSON   보관 시간이 지나면 지워진다
 * </pre>
 */
@Component
class RedisRecommendationSnapshotStore implements RecommendationSnapshotStore {

    static final String KEY_PREFIX = "payment:rec:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    RedisRecommendationSnapshotStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Long paymentId, RecommendationResult result, Duration retention) {
        try {
            redis.opsForValue().set(KEY_PREFIX + paymentId, objectMapper.writeValueAsString(result), retention);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("추천 결과를 JSON으로 바꾸지 못했습니다.", e);
        }
    }

    @Override
    public Optional<RecommendationResult> find(Long paymentId) {
        String json = redis.opsForValue().get(KEY_PREFIX + paymentId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, RecommendationResult.class));
        } catch (JsonProcessingException e) {
            // 보관 중 형식이 바뀐 것(배포 직후). 없는 것으로 보고 승인 때 다시 계산한다(Q2).
            return Optional.empty();
        }
    }

    @Override
    public void delete(Long paymentId) {
        redis.delete(KEY_PREFIX + paymentId);
    }
}
