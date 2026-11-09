package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Attempt;
import com.yamu.be.payment.approve.ApprovalAttemptStore.Reservation;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import com.yamu.be.support.RedisTestSupport;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 시도 기록의 Redis 스크립트(예약·지우기·결과 기록)를 실제 Redis로 확인한다. 최대 횟수는 application-test.yml의 3회다. */
class ApprovalAttemptStoreTest extends RedisTestSupport {

    static final long PAYMENT_ID = 1024L;
    static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 10, 22, 5, 30, 10);

    @Autowired ApprovalAttemptStore store;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void 예약은_1부터_번호를_매기고_최대_횟수에_닿으면_붙이지_않는다() {
        assertThat(store.reserve(PAYMENT_ID, draft("k1", 15L)).attempt().no()).isEqualTo(1);
        assertThat(store.reserve(PAYMENT_ID, draft("k2", 16L)).attempt().no()).isEqualTo(2);
        assertThat(store.reserve(PAYMENT_ID, draft("k3", 17L)).attempt().no()).isEqualTo(3);

        Reservation fourth = store.reserve(PAYMENT_ID, draft("k4", 18L));

        assertThat(fourth.status()).isEqualTo(Reservation.Status.LIMIT_REACHED);
        assertThat(fourth.attempt()).isNull();
        assertThat(store.count(PAYMENT_ID)).isEqualTo(3);
        assertThat(redis.getExpire(ApprovalAttemptStore.KEY_PREFIX + PAYMENT_ID, TimeUnit.HOURS)).isBetween(23L, 24L);
    }

    @Test
    void 동시에_예약해도_번호가_겹치거나_최대_횟수를_넘지_않는다() throws Exception {
        store.reserve(PAYMENT_ID, draft("k0", 15L));
        store.reserve(PAYMENT_ID, draft("k1", 16L));
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Reservation>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                String key = "c" + i;
                Callable<Reservation> call = () -> {
                    go.await();
                    return store.reserve(PAYMENT_ID, draft(key, 17L));
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            List<Reservation> reservations = new ArrayList<>();
            for (Future<Reservation> future : results) {
                reservations.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(reservations.stream().filter(r -> r.status() == Reservation.Status.RESERVED)).hasSize(1);
            assertThat(reservations).allMatch(r -> r.status() != Reservation.Status.RESERVED
                    || r.attempt().no() == 3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.list(PAYMENT_ID)).extracting(Attempt::no).containsExactly(1, 2, 3);
    }

    @Test
    void 선점하지_못한_예약을_지우면_횟수를_쓰지_않고_다음_예약이_같은_번호를_받는다() {
        store.reserve(PAYMENT_ID, draft("k1", 15L));
        Attempt lost = store.reserve(PAYMENT_ID, draft("k2", 16L)).attempt();

        store.release(PAYMENT_ID, lost);

        assertThat(store.count(PAYMENT_ID)).isEqualTo(1);
        assertThat(store.findByKey(PAYMENT_ID, "k2")).isEmpty();
        assertThat(store.reserve(PAYMENT_ID, draft("k3", 16L)).attempt().no()).isEqualTo(2);
    }

    @Test
    void 결과를_자리_그대로_적고_키로_찾으며_거절된_카드만_따로_센다() {
        Attempt first = store.reserve(PAYMENT_ID, draft("k1", 15L)).attempt();
        PaymentApprovalResponse declined = new PaymentApprovalResponse(PAYMENT_ID, PaymentStatus.DECLINED, null, null,
                null, 15_000, null, null, List.of(), null, null, List.of(), null, List.of(),
                new PaymentApprovalResponse.Failure("INSUFFICIENT_BALANCE", "연결 계좌 잔액이 부족합니다.", null), true, 2,
                List.of());
        store.recordResult(PAYMENT_ID, first, first.withResult(Attempt.DECLINED, "INSUFFICIENT_BALANCE", null, null,
                declined));
        Attempt second = store.reserve(PAYMENT_ID, draft("k2", 16L)).attempt();
        store.recordResult(PAYMENT_ID, second, second.withResult(Attempt.APPROVED, null, "FIN-0001", 14_000L, null));

        List<Attempt> attempts = store.list(PAYMENT_ID);
        assertThat(attempts).extracting(Attempt::no).containsExactly(1, 2);
        assertThat(attempts).extracting(Attempt::outcome).containsExactly(Attempt.DECLINED, Attempt.APPROVED);
        assertThat(attempts.get(1).finTransactionNo()).isEqualTo("FIN-0001");
        assertThat(attempts.get(1).chargedAmount()).isEqualTo(14_000L);

        Attempt found = store.findByKey(PAYMENT_ID, "k1").orElseThrow();
        assertThat(found.fingerprint()).isEqualTo("1024:15");
        assertThat(found.response().failure().code()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(store.declinedCardIds(PAYMENT_ID)).containsExactly(15L);
        assertThat(store.triedCardIds(PAYMENT_ID)).containsExactly(15L, 16L);
    }

    @Test
    void 실적_인정_값을_남기고_읽으며_이_칸이_없던_기록은_null로_읽는다() {
        // Task 5.2.11: 복구가 승인으로 확정할 때 이 값으로 실적을 적는다. 매퍼 설정이 바뀌어 값이 빠지면 조용히 미확인이 된다
        CardSummary card = new CardSummary(15L, 3L, CardType.CHECK, false, null, "1234", "TEST", "테스트 카드");
        var recognition = new PerformanceRecognition(15L, YearMonth.of(2026, 11), 15_000L);
        Attempt reserved = store.reserve(PAYMENT_ID, Attempt.start("k1", PAYMENT_ID + ":15", card, 1, true, STARTED_AT,
                null, recognition)).attempt();
        store.attachFinRequestNo(PAYMENT_ID, reserved.no(), "REQ-0001");

        assertThat(store.findByFinRequestNo(PAYMENT_ID, "REQ-0001").orElseThrow().recognition()).isEqualTo(recognition);

        // 310 이전에 남긴 기록: recognition 칸이 없다
        String key = ApprovalAttemptStore.KEY_PREFIX + PAYMENT_ID;
        String stored = redis.opsForList().index(key, 0);
        assertThat(stored).contains("\"recognition\"");
        String legacy = stored.replaceAll(",\"recognition\":\\{[^}]*\\}", "");
        assertThat(legacy).doesNotContain("recognition");
        redis.opsForList().set(key, 0, legacy);

        assertThat(store.findByFinRequestNo(PAYMENT_ID, "REQ-0001").orElseThrow().recognition()).isNull();
    }

    private static Attempt draft(String key, Long userCardId) {
        CardSummary card = new CardSummary(userCardId, 3L, CardType.CREDIT, false, null, "1234", "TEST", "테스트 카드");
        return Attempt.start(key, PAYMENT_ID + ":" + userCardId, card, 1, true, STARTED_AT);
    }
}
