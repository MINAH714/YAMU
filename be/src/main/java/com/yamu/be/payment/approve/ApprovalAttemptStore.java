package com.yamu.be.payment.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 결제별 승인 시도 기록(10/28 BE2 결정: Redis, 결제 ID 기준). DB 행에는 마지막 시도의 카드·거절 사유만 남고, 앞 시도는 여기에 남는다.
 *
 * <pre>
 * payment:approve:attempts:{paymentId} → LIST [ JSON {@link Attempt} ]
 * payment:approve:start:{paymentId}    → 시도 예약 ~ PROCESSING 선점 사이의 잠금(RedisLocks)
 * </pre>
 *
 * <ul>
 *   <li>시도는 PROCESSING 선점(tx1) <b>앞</b>에서 예약한다({@link #reserve}). 예약이 Redis 장애로 실패하면 결제 행은 바뀌지 않는다.
 *       선점에서 지거나 마감이 지나면 예약을 지운다({@link #release}). 선점에서 진 요청은 세지 않는다.</li>
 *   <li>예약은 "목록 길이가 내가 본 값이고 최대 횟수 미만일 때만 붙이기"를 한 스크립트로 한다. 같은 결제의 예약~선점은 시작 잠금으로 한 줄로 세우고,
 *       잠금이 시간을 넘겨 풀려도 길이 비교로 두 요청이 같은 번호를 받지 않는다.</li>
 *   <li>승인 실행 결과(거래 번호·출금액·응답)는 DB에 저장(tx2)하기 <b>전에</b> 적는다({@link #recordResult}). tx2가 실패해 결제가 PROCESSING에
 *       남아도 복구가 이 기록을 근거로 쓸 수 있고, 같은 키 재전송은 DB가 저장을 마친 뒤 이 응답을 다시 돌려준다.</li>
 * </ul>
 *
 * 임시: Redis가 비워지면 재승인 횟수가 0부터 다시 세어진다(MR에 표시). 예약 뒤 서버가 죽어 지우지 못한 예약은 횟수 하나를 쓴다(덜 세지 않고 더 센다).
 */
@Component
public class ApprovalAttemptStore {

    static final String KEY_PREFIX = "payment:approve:attempts:";
    static final String START_LOCK_PREFIX = "payment:approve:start:";
    private static final int UPDATE_RETRIES = 3;

    /* KEYS[1] 시도 목록. ARGV[1] 내가 본 길이, ARGV[2] 최대 횟수, ARGV[3] 새 시도 JSON, ARGV[4] 보관 시간(밀리초). 반환: 새 길이, -1 횟수 초과, -2 길이가 바뀜 */
    private static final RedisScript<Long> RESERVE_SCRIPT = RedisScript.of("""
            local size = redis.call('LLEN', KEYS[1])
            if size >= tonumber(ARGV[2]) then
              return -1
            end
            if size ~= tonumber(ARGV[1]) then
              return -2
            end
            redis.call('RPUSH', KEYS[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return size + 1
            """, Long.class);

    /* KEYS[1] 시도 목록. ARGV[1] 지금 값, ARGV[2] 바꿀 값. 반환: 1 바꿈, 0 없음 */
    private static final RedisScript<Long> REPLACE_SCRIPT = RedisScript.of("""
            local index = redis.call('LPOS', KEYS[1], ARGV[1])
            if not index then
              return 0
            end
            redis.call('LSET', KEYS[1], index, ARGV[2])
            return 1
            """, Long.class);

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

    /** 예약 ~ PROCESSING 선점을 한 줄로 세우는 잠금 키. */
    public String startLockKey(Long paymentId) {
        return START_LOCK_PREFIX + paymentId;
    }

    /**
     * 새 시도를 예약한다. 번호는 목록 길이다(1부터).
     *
     * @return 예약한 시도. 최대 횟수에 닿았으면 LIMIT_REACHED, 길이를 본 뒤 다른 요청이 먼저 예약했으면 CONTENDED
     */
    public Reservation reserve(Long paymentId, Attempt draft) {
        int seen = count(paymentId);
        Attempt attempt = draft.withNo(seen + 1);
        Long result = redis.execute(RESERVE_SCRIPT, List.of(key(paymentId)), Integer.toString(seen),
                Integer.toString(properties.maxAttempts()), write(attempt),
                Long.toString(properties.attemptTtl().toMillis()));
        if (result == null || result == -2L) {
            return Reservation.CONTENDED;
        }
        if (result == -1L) {
            return Reservation.LIMIT_REACHED;
        }
        return new Reservation(attempt);
    }

    /** 선점하지 못했거나 금융망에 보내지 못한 시도를 지운다. 횟수를 쓰지 않게 한다. */
    public void release(Long paymentId, Attempt attempt) {
        rawOf(paymentId, sameAttempt(attempt)).ifPresent(json -> redis.opsForList().remove(key(paymentId), -1, json));
    }

    /**
     * 승인 실행 결과를 적는다. DB 저장(tx2) 전에 부른다. 그 사이 붙은 출금 요청 번호({@link #attachFinRequestNo})는 지킨다.
     *
     * @return 적었으면 true. 시도 기록이 없으면(보관 종료·비워짐) false
     */
    public boolean recordResult(Long paymentId, Attempt attempt, Attempt result) {
        return update(paymentId, sameAttempt(attempt), attempt.no(), current -> result.finRequestNo() != null
                ? result : result.withFinRequestNo(current.finRequestNo()));
    }

    /**
     * 출금에 쓴 기관거래고유번호를 시도에 남긴다. DB 행은 다음 시도에서 번호를 지우므로 옛 번호는 여기서만 볼 수 있다.
     * 출금 실행은 결제별 정산 잠금 안이라 그 결제에 실행 중인 시도는 하나뿐이다. 그래서 번호로 찾는다.
     */
    public boolean attachFinRequestNo(Long paymentId, int attemptNo, String finRequestNo) {
        return update(paymentId, row -> row.no() == attemptNo, attemptNo,
                current -> current.withFinRequestNo(finRequestNo));
    }

    /** 그 출금 요청 번호로 출금한 시도. 복구가 결과를 확정할 때 닫는다. */
    public Optional<Attempt> findByFinRequestNo(Long paymentId, String finRequestNo) {
        return list(paymentId).stream().filter(a -> finRequestNo.equals(a.finRequestNo())).findFirst();
    }

    /** 아직 결과가 적히지 않은 마지막 시도. 요청 번호 없이 되돌린 결제의 시도를 지울 때 쓴다. */
    public Optional<Attempt> lastProcessing(Long paymentId) {
        List<Attempt> all = list(paymentId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (Attempt.PROCESSING.equals(all.get(i).outcome())) {
                return Optional.of(all.get(i));
            }
        }
        return Optional.empty();
    }

    /** 그 시도의 지금 값을 읽어 바꾼 값으로 갈아 끼운다. 그 사이 다른 쓰기가 끼면 다시 읽는다. */
    private boolean update(Long paymentId, Predicate<Attempt> which, int attemptNo, UnaryOperator<Attempt> change) {
        for (int i = 0; i < UPDATE_RETRIES; i++) {
            Optional<String> current = rawOf(paymentId, which);
            if (current.isEmpty()) {
                return false;
            }
            Attempt changed = change.apply(read(current.get()));
            Long replaced = redis.execute(REPLACE_SCRIPT, List.of(key(paymentId)), current.get(), write(changed));
            if (replaced != null && replaced == 1L) {
                return true;
            }
        }
        throw new IllegalStateException("승인 시도 기록을 고치지 못했습니다 (결제 " + paymentId + ", 시도 " + attemptNo + ")");
    }

    /** 같은 키·같은 번호의 시도(앞 예약이 지워져 자리가 바뀌어도 찾는다). */
    private static Predicate<Attempt> sameAttempt(Attempt attempt) {
        return row -> row.no() == attempt.no() && Objects.equals(row.idempotencyKey(), attempt.idempotencyKey());
    }

    /** 조건에 맞는 시도 하나의 저장된 JSON. */
    private Optional<String> rawOf(Long paymentId, Predicate<Attempt> which) {
        List<String> rows = redis.opsForList().range(key(paymentId), 0, -1);
        if (rows == null) {
            return Optional.empty();
        }
        return rows.stream().filter(json -> {
            Attempt row = read(json);
            return row != null && which.test(row);
        }).findFirst();
    }

    /** 이 Idempotency-Key가 만든 시도. 같은 키는 한 번만 실행하므로 많아야 하나다. */
    public Optional<Attempt> findByKey(Long paymentId, String idempotencyKey) {
        return list(paymentId).stream().filter(a -> idempotencyKey.equals(a.idempotencyKey())).findFirst();
    }

    /** 지금까지 승인을 시도한 카드. 거절 응답의 다음 카드 추천에서 뺀다. */
    public Set<Long> triedCardIds(Long paymentId) {
        Set<Long> tried = new LinkedHashSet<>();
        for (Attempt attempt : list(paymentId)) {
            if (attempt.card() != null) {
                tried.add(attempt.card().userCardId());
            }
        }
        return tried;
    }

    /** 이 결제에서 확정 거절된 카드. 같은 카드로 다시 승인할 수 없다(명세: 재승인은 다른 userCardId). */
    public Set<Long> declinedCardIds(Long paymentId) {
        Set<Long> declined = new LinkedHashSet<>();
        for (Attempt attempt : list(paymentId)) {
            if (Attempt.DECLINED.equals(attempt.outcome()) && attempt.card() != null) {
                declined.add(attempt.card().userCardId());
            }
        }
        return declined;
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

    /** 예약 결과. RESERVED일 때만 attempt가 있다. */
    public record Reservation(Status status, Attempt attempt) {

        static final Reservation LIMIT_REACHED = new Reservation(Status.LIMIT_REACHED, null);
        static final Reservation CONTENDED = new Reservation(Status.CONTENDED, null);

        Reservation(Attempt attempt) {
            this(Status.RESERVED, attempt);
        }

        public enum Status {
            RESERVED,
            /** 최대 횟수에 닿음 */
            LIMIT_REACHED,
            /** 길이를 본 뒤 다른 요청이 먼저 예약함 */
            CONTENDED
        }
    }

    /**
     * 승인 시도 한 건.
     *
     * @param no               이 결제의 몇 번째 시도인지(1부터). 예약할 때 정한다
     * @param fingerprint      요청 내용(결제 번호·고른 카드). 같은 키 재전송을 대조한다
     * @param card             승인할 카드
     * @param recommendedRank  추천 순위. 후보가 아니면 null
     * @param outcome          PROCESSING(실행 중이거나 결과 불명) · APPROVED · DECLINED
     * @param failureCode      거절이면 사유, 아니면 null
     * @param finRequestNo     체크카드 출금에 쓴 기관거래고유번호. 출금 전·신용카드·0원은 null
     * @param finTransactionNo 체크카드 출금의 금융망 거래 번호. 없으면 null
     * @param chargedAmount    승인으로 빠져나간(체크카드)·청구될(신용카드) 금액. 승인이 아니면 null
     * @param response         실행 결과로 만든 응답. 같은 키 재전송에 돌려준다. 실행 전이면 null
     * @param basis            이 시도의 승인액을 정한 추천 후보(승인 때 다시 계산한 값, Task 5.2.5). 결과 불명 출금을 복구가
     *                         승인으로 확정할 때 이 값으로 원장을 적는다. 추천 밖의 카드이거나 이 칸이 생기기 전의 기록이면 null
     * @param recognition      이 시도 카드의 실적 인정 값(BE3 계산, Task 5.2.11). 복구가 승인으로 확정할 때 이 값으로 실적을 적는다.
     *                         계산되지 않았거나 이 칸이 생기기 전의 기록이면 null(인정액 미확인)
     */
    public record Attempt(int no, String idempotencyKey, String fingerprint, CardSummary card,
            Integer recommendedRank, boolean userSelected, LocalDateTime startedAt, String outcome,
            String failureCode, String finRequestNo, String finTransactionNo, Long chargedAmount,
            PaymentApprovalResponse response, Candidate basis, PerformanceRecognition recognition) {

        /** 실적 인정 값 없는 시도(테스트·기존 호출 호환). */
        public Attempt(int no, String idempotencyKey, String fingerprint, CardSummary card,
                Integer recommendedRank, boolean userSelected, LocalDateTime startedAt, String outcome,
                String failureCode, String finRequestNo, String finTransactionNo, Long chargedAmount,
                PaymentApprovalResponse response, Candidate basis) {
            this(no, idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt, outcome, failureCode,
                    finRequestNo, finTransactionNo, chargedAmount, response, basis, null);
        }

        public static final String PROCESSING = "PROCESSING";
        public static final String APPROVED = "APPROVED";
        public static final String DECLINED = "DECLINED";

        static Attempt start(String idempotencyKey, String fingerprint, CardSummary card, Integer recommendedRank,
                boolean userSelected, LocalDateTime startedAt) {
            return start(idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt, null, null);
        }

        static Attempt start(String idempotencyKey, String fingerprint, CardSummary card, Integer recommendedRank,
                boolean userSelected, LocalDateTime startedAt, Candidate basis) {
            return start(idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt, basis, null);
        }

        static Attempt start(String idempotencyKey, String fingerprint, CardSummary card, Integer recommendedRank,
                boolean userSelected, LocalDateTime startedAt, Candidate basis, PerformanceRecognition recognition) {
            return new Attempt(0, idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt,
                    PROCESSING, null, null, null, null, null, basis, recognition);
        }

        Attempt withNo(int newNo) {
            return new Attempt(newNo, idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt,
                    outcome, failureCode, finRequestNo, finTransactionNo, chargedAmount, response, basis, recognition);
        }

        Attempt withFinRequestNo(String newFinRequestNo) {
            return new Attempt(no, idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt,
                    outcome, failureCode, newFinRequestNo, finTransactionNo, chargedAmount, response, basis, recognition);
        }

        Attempt withResult(String newOutcome, String newFailureCode, String newFinTransactionNo,
                Long newChargedAmount, PaymentApprovalResponse newResponse) {
            return new Attempt(no, idempotencyKey, fingerprint, card, recommendedRank, userSelected, startedAt,
                    newOutcome, newFailureCode, finRequestNo, newFinTransactionNo, newChargedAmount, newResponse,
                    basis, recognition);
        }
    }
}
