package com.yamu.be.payment.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제별 승인 시도 기록(10/28 BE2 결정: Redis, 결제 ID 기준). DB 행에는 마지막 시도의 카드·거절 사유·요청 번호만 남고, 앞 시도는 여기에 남는다.
 *
 * <pre>
 * payment:approve:attempts:{paymentId} → LIST [ JSON { no, userCardId, idempotencyKey, startedAt, outcome, failureCode, finRequestNo } ]
 * </pre>
 *
 * 시도는 결제가 PROCESSING으로 저장된 뒤에 기록한다. 선점에서 진 요청은 세지 않는다.
 * 금융망에 보내지도 못한 시도({@link #NOT_SENT})는 번호는 받지만 재승인 횟수·시도한 카드에서 뺀다.
 * 임시: Redis가 비워지면 재승인 횟수가 0부터 다시 세어진다(MR에 표시).
 */
@Component
public class ApprovalAttemptStore {

    static final String KEY_PREFIX = "payment:approve:attempts:";
    /** 시도 결과: 실행 중 */
    public static final String PROCESSING = "PROCESSING";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    /** 금융망에 보내지 못해 결제를 되돌렸다. 횟수에 세지 않는다 */
    public static final String NOT_SENT = "NOT_SENT";

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

    /** 재승인 횟수에 세는 시도 수. 금융망에 보내지 못한 시도는 뺀다. */
    public int count(Long paymentId) {
        return (int) list(paymentId).stream().filter(Attempt::counts).count();
    }

    /** 새 시도를 뒤에 붙이고 돌려준다. 번호는 목록 길이다(1부터). */
    public Attempt start(Long paymentId, Long userCardId, String idempotencyKey, LocalDateTime startedAt) {
        Attempt placeholder = new Attempt(0, userCardId, idempotencyKey, startedAt, PROCESSING, null, null);
        Long size = redis.opsForList().rightPush(key(paymentId), write(placeholder));
        int no = size == null ? 1 : size.intValue();
        Attempt attempt = placeholder.withNo(no);
        redis.opsForList().set(key(paymentId), no - 1L, write(attempt));
        redis.expire(key(paymentId), properties.attemptTtl());
        return attempt;
    }

    /** 시도 결과를 적는다. 그 사이 적힌 출금 요청 번호({@link #attachFinRequestNo})는 Redis의 값을 지킨다. */
    public void finish(Attempt attempt, String outcome, String failureCode, Long paymentId) {
        String finRequestNo = find(paymentId, attempt.no()).map(Attempt::finRequestNo).orElse(attempt.finRequestNo());
        Attempt done = new Attempt(attempt.no(), attempt.userCardId(), attempt.idempotencyKey(), attempt.startedAt(),
                outcome, failureCode, finRequestNo);
        redis.opsForList().set(key(paymentId), attempt.no() - 1L, write(done));
    }

    /** 출금에 쓴 기관거래고유번호를 시도에 남긴다. DB 행은 다음 시도에서 번호를 지우므로 옛 번호는 여기서만 볼 수 있다. */
    public void attachFinRequestNo(Long paymentId, int attemptNo, String finRequestNo) {
        find(paymentId, attemptNo).ifPresent(attempt -> redis.opsForList().set(key(paymentId), attemptNo - 1L,
                write(new Attempt(attempt.no(), attempt.userCardId(), attempt.idempotencyKey(), attempt.startedAt(),
                        attempt.outcome(), attempt.failureCode(), finRequestNo))));
    }

    /** 아직 결과가 적히지 않은 마지막 시도. 복구가 결과를 확정할 때 닫는다. */
    public Optional<Attempt> lastProcessing(Long paymentId) {
        List<Attempt> all = list(paymentId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (PROCESSING.equals(all.get(i).outcome())) {
                return Optional.of(all.get(i));
            }
        }
        return Optional.empty();
    }

    /** 지금까지 승인을 시도한 카드. 거절 응답의 다음 카드 추천에서 뺀다. 금융망에 보내지 못한 시도는 세지 않는다. */
    public Set<Long> triedCardIds(Long paymentId) {
        Set<Long> tried = new LinkedHashSet<>();
        for (Attempt attempt : list(paymentId)) {
            if (attempt.userCardId() != null && attempt.counts()) {
                tried.add(attempt.userCardId());
            }
        }
        return tried;
    }

    private Optional<Attempt> find(Long paymentId, int attemptNo) {
        String json = redis.opsForList().index(key(paymentId), attemptNo - 1L);
        return json == null ? Optional.empty() : Optional.ofNullable(read(json));
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
     * @param outcome      PROCESSING(실행 중) · APPROVED · DECLINED · NOT_SENT
     * @param failureCode  거절이면 사유, 아니면 null
     * @param finRequestNo 출금에 쓴 기관거래고유번호. 출금 전·신용카드는 null
     */
    public record Attempt(int no, Long userCardId, String idempotencyKey, LocalDateTime startedAt, String outcome,
            String failureCode, String finRequestNo) {

        Attempt withNo(int newNo) {
            return new Attempt(newNo, userCardId, idempotencyKey, startedAt, outcome, failureCode, finRequestNo);
        }

        /** 재승인 횟수에 세는 시도인지. 금융망에 보내지 못한 시도는 아무 일도 없던 것으로 본다. */
        boolean counts() {
            return !NOT_SENT.equals(outcome);
        }
    }
}
