package com.yamu.be.payment.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제별 승인 시도 기록(10/28 BE2 결정: Redis, 결제 ID 기준). DB 행에는 마지막 시도의 카드·거절 사유만 남고, 앞 시도는 여기에 남는다.
 *
 * <pre>
 * payment:approve:attempts:{paymentId} → LIST [ JSON { no, userCardId, idempotencyKey, startedAt, outcome, failureCode } ]
 * </pre>
 *
 * 시도는 결제가 PROCESSING으로 저장된 뒤에 기록한다. 선점에서 진 요청은 세지 않는다.
 * 임시: Redis가 비워지면 재승인 횟수가 0부터 다시 세어진다(MR에 표시).
 */
@Component
public class ApprovalAttemptStore {

    static final String KEY_PREFIX = "payment:approve:attempts:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PaymentApprovalProperties properties;

    public ApprovalAttemptStore(StringRedisTemplate redis, ObjectMapper objectMapper,
            PaymentApprovalProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<Attempt> list(Long paymentId) {
        List<String> rows = redis.opsForList().range(key(paymentId), 0, -1);
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(this::read).filter(Objects::nonNull).toList();
    }

    public int count(Long paymentId) {
        Long size = redis.opsForList().size(key(paymentId));
        return size == null ? 0 : size.intValue();
    }

    /** 새 시도를 뒤에 붙이고 돌려준다. 번호는 목록 길이다(1부터). */
    public Attempt start(Long paymentId, Long userCardId, String idempotencyKey, LocalDateTime startedAt) {
        Attempt placeholder = new Attempt(0, userCardId, idempotencyKey, startedAt, "PROCESSING", null);
        Long size = redis.opsForList().rightPush(key(paymentId), write(placeholder));
        int no = size == null ? 1 : size.intValue();
        Attempt attempt = placeholder.withNo(no);
        redis.opsForList().set(key(paymentId), no - 1L, write(attempt));
        redis.expire(key(paymentId), properties.attemptTtl());
        return attempt;
    }

    /** 시도 결과를 적는다. */
    public void finish(Attempt attempt, String outcome, String failureCode, Long paymentId) {
        Attempt done = new Attempt(attempt.no(), attempt.userCardId(), attempt.idempotencyKey(), attempt.startedAt(),
                outcome, failureCode);
        redis.opsForList().set(key(paymentId), attempt.no() - 1L, write(done));
    }

    /** 지금까지 승인을 시도한 카드. 거절 응답의 다음 카드 추천에서 뺀다. */
    public Set<Long> triedCardIds(Long paymentId) {
        Set<Long> tried = new LinkedHashSet<>();
        for (Attempt attempt : list(paymentId)) {
            if (attempt.userCardId() != null) {
                tried.add(attempt.userCardId());
            }
        }
        return tried;
    }

    private static String key(Long paymentId) {
        return KEY_PREFIX + paymentId;
    }

    private String write(Attempt attempt) {
        try {
            return objectMapper.writeValueAsString(attempt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Attempt read(String json) {
        try {
            return objectMapper.readValue(json, Attempt.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 승인 시도 한 건.
     *
     * @param outcome     PROCESSING(실행 중) · APPROVED · DECLINED
     * @param failureCode 거절이면 사유, 아니면 null
     */
    public record Attempt(int no, Long userCardId, String idempotencyKey, LocalDateTime startedAt, String outcome,
            String failureCode) {

        Attempt withNo(int newNo) {
            return new Attempt(newNo, userCardId, idempotencyKey, startedAt, outcome, failureCode);
        }
    }
}
