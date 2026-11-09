package com.yamu.be.global.job;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.redis.RedisLocks;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 같은 정기 작업이 겹쳐 돌지 않게 잡는 Redis 잠금(작업 이름당 하나). 여러 서버의 스케줄러와 운영 API의 수동 실행이 모두 이 잠금을 거친다.
 * 이미 잡혀 있으면 기다리지 않고 409 {@code JOB_ALREADY_RUNNING}이다. 스케줄러는 그 예외를 받아 이번 회차를 건너뛴다.
 *
 * <p>TTL은 작업 하나가 끝나는 시간보다 넉넉히 둔다(대상 수백 건 × 금융망 호출 10초 한계를 생각해 10분). TTL 안에 끝나지 않으면 잠금이 풀려
 * 다른 실행과 겹칠 수 있으므로, 대상마다 상태·버전으로 한 번만 처리되게 하는 것은 각 작업의 몫이다.
 */
@Component
public class JobLocks {

    static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "job:";
    private static final String SUFFIX = ":lock";

    private final RedisLocks locks;

    public JobLocks(RedisLocks locks) {
        this.locks = locks;
    }

    public static String lockKey(String jobName) {
        return PREFIX + jobName + SUFFIX;
    }

    /** 잠금을 잡고 work를 돌린 뒤 푼다. 이미 잡혀 있으면 409. */
    public <T> T locked(String jobName, Supplier<T> work) {
        Optional<RedisLocks.Lock> lock = locks.tryLock(lockKey(jobName), LOCK_TTL);
        if (lock.isEmpty()) {
            throw new BusinessException(ErrorCode.JOB_ALREADY_RUNNING);
        }
        try (RedisLocks.Lock ignored = lock.get()) {
            return work.get();
        }
    }
}
