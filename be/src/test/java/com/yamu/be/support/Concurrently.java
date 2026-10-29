package com.yamu.be.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 여러 요청을 "동시에" 출발시켜 결과를 모으는 테스트 도우미. 연타·재시도·두 기기 동시 요청 같은 경합을 재현할 때 쓴다.
 *
 * <pre>
 * List&lt;Integer&gt; statuses = Concurrently.run(2, () -&gt; signup(token).andReturn().getResponse().getStatus());
 * assertThat(statuses).containsExactlyInAnyOrder(201, 409);
 * </pre>
 *
 * 모든 작업이 출발선(CountDownLatch)에서 기다리다 한꺼번에 시작한다. 그래도 실제로 겹치는지는 스케줄러에 달려 있어,
 * 반드시 겹쳐야 하는 테스트는 가짜 외부 호출에 잠깐 멈춤(지연)을 넣어 겹치는 구간을 만든다.
 */
public final class Concurrently {

    private static final long TIMEOUT_SECONDS = 30;

    private Concurrently() {
    }

    /** 같은 작업을 count개 동시에 실행한다. 결과는 끝난 순서가 아니라 작업 순서다. */
    public static <T> List<T> run(int count, Callable<T> task) throws Exception {
        return run(Collections.nCopies(count, task));
    }

    /** 서로 다른 작업들을 동시에 실행한다. 하나라도 예외로 끝나면 그 예외(원인)를 던진다. */
    public static <T> List<T> run(List<? extends Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
