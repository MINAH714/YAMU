package com.yamu.be.payment.cancel;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 체크카드 취소 환불 입금을 보낸 시각을 결제별로 기억한다(Task 5.3.3 이중 환불 방지).
 *
 * <pre>
 * payment:cancel:check-refund:{paymentId} → 입금을 보낸 시각(ISO-8601 UTC)
 * </pre>
 *
 * <p>입금 결과를 모를 때(시간 초과 등) SSAFY가 아직 처리 중이면 거래내역에 태그가 바로 보이지 않는다. 그때 사용자가 곧바로 다시 취소하면
 * "입금 없음"으로 보고 또 입금할 수 있다. 그래서 입금을 보내기 <b>전에</b> 시각을 남기고, 유예 시간 안의 재시도는 입금하지 않는다.
 * 이 기록을 남기지 못하면 입금하지 않는다.
 *
 * <p>임시: 결제 행과 환불 테이블에 체크카드 환불 입금을 적을 칸이 없어 Redis에 둔다. 보관 시간({@link #RETENTION})이 지나거나
 * Redis 데이터가 사라지면 유예 검사 없이 거래내역의 태그로만 판단한다(그때는 이미 유예가 한참 지난 뒤라 태그가 보인다고 본다).
 */
@Component
public class CheckRefundAttemptStore {

    static final String KEY_PREFIX = "payment:cancel:check-refund:";
    /** 임시: 유예(초 단위)보다 넉넉히. 지나면 거래내역 태그로만 판단한다 */
    static final Duration RETENTION = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public CheckRefundAttemptStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 마지막으로 입금을 보낸 시각. 기록이 없거나 읽을 수 없으면 empty. */
    public Optional<Instant> lastSentAt(Long paymentId) {
        String value = redis.opsForValue().get(key(paymentId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 입금을 보내기 직전에 부른다. 실패하면 예외가 나고, 받는 쪽은 입금하지 않는다. */
    public void recordSending(Long paymentId, Instant at) {
        redis.opsForValue().set(key(paymentId), at.toString(), RETENTION);
    }

    /** 돈이 움직이지 않은 것이 확실할 때(거절·못 보냄) 지워 바로 다시 취소할 수 있게 한다. */
    public void clear(Long paymentId) {
        redis.delete(key(paymentId));
    }

    private static String key(Long paymentId) {
        return KEY_PREFIX + paymentId;
    }
}
