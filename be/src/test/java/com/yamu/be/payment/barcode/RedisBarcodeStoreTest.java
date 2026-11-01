package com.yamu.be.payment.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.RedisTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class RedisBarcodeStoreTest extends RedisTestSupport {

    static final Instant ISSUED_AT = Instant.parse("2026-10-28T05:30:00Z");
    static final Duration RETENTION = Duration.ofMinutes(10);

    @Autowired RedisBarcodeStore store;
    @Autowired StringRedisTemplate redis;

    final BarcodeTokenGenerator tokens = new BarcodeTokenGenerator();

    @BeforeEach
    void clearRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void 저장한_바코드를_그대로_읽는다() {
        Barcode barcode = issued(7L);

        assertThat(store.issue(barcode, RETENTION)).isTrue();

        assertThat(store.find(barcode.token())).contains(barcode);
    }

    @Test
    void 키_구조는_barcode_code_토큰_해시와_barcode_user_사용자_문자열이다() {
        Barcode barcode = issued(7L);
        store.issue(barcode, RETENTION);

        Map<Object, Object> fields = redis.opsForHash().entries("barcode:code:" + barcode.token());
        assertThat(fields).containsOnlyKeys("userId", "status", "issuedAt", "expiresAt");
        assertThat(fields).containsEntry("userId", "7")
                .containsEntry("status", "ISSUED")
                .containsEntry("issuedAt", Long.toString(ISSUED_AT.toEpochMilli()))
                .containsEntry("expiresAt", Long.toString(ISSUED_AT.plusSeconds(60).toEpochMilli()));
        assertThat(redis.opsForValue().get("barcode:user:7")).isEqualTo(barcode.token());
    }

    @Test
    void 사용_유효기간이_지나도_보관_중이면_읽히고_저장된_상태는_그대로_ISSUED다() {
        // 논리적 만료(60초)는 서비스가 expiresAt으로 판단하고, 저장소는 보관 시간(10분)까지 지우지 않는다
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Barcode stale = new Barcode(tokens.next(), 7L, BarcodeStatus.ISSUED, longAgo, longAgo.plusSeconds(60));
        store.issue(stale, RETENTION);

        Barcode found = store.find(stale.token()).orElseThrow();
        assertThat(found.status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(found.statusAt(Instant.now())).isEqualTo(BarcodeStatus.EXPIRED);
    }

    @Test
    void 보관_시간이_실제로_지나면_Redis가_키를_지워_찾을_수_없다() throws Exception {
        // 실제 TTL 동작 확인. 10분을 기다리지 않도록 보관 시간을 1초로 준다
        Barcode barcode = issued(7L);
        store.issue(barcode, Duration.ofSeconds(1));
        assertThat(store.find(barcode.token())).isPresent();

        long deadline = System.currentTimeMillis() + 5_000;
        while (store.find(barcode.token()).isPresent() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(store.find(barcode.token())).isEmpty();
        assertThat(redis.hasKey("barcode:code:" + barcode.token())).isFalse();
        assertThat(redis.hasKey("barcode:user:7")).isFalse();
    }

    @Test
    void 다시_발급하면_이전_바코드의_보관_시간은_그대로_두고_사용자_키만_새로_잡는다() throws Exception {
        Barcode first = issued(7L);
        store.issue(first, Duration.ofMinutes(2));
        Thread.sleep(1_100);

        store.issue(issued(7L), RETENTION);

        Long firstTtl = redis.getExpire("barcode:code:" + first.token(), TimeUnit.MILLISECONDS);
        Long userTtl = redis.getExpire("barcode:user:7", TimeUnit.MILLISECONDS);
        // 이전 바코드는 처음 받은 2분에서 흐른 만큼 남아 있고(연장·삭제 없음), 사용자 키는 새 보관 시간 10분으로 다시 잡힌다
        assertThat(firstTtl).isBetween(Duration.ofSeconds(100).toMillis(), Duration.ofSeconds(119).toMillis());
        assertThat(userTtl).isBetween(RETENTION.minusSeconds(5).toMillis(), RETENTION.toMillis());
    }

    @Test
    void 보관_시간이_지나면_지워지도록_만료를_건다() {
        Barcode barcode = issued(7L);
        store.issue(barcode, RETENTION);

        Long barcodeTtl = redis.getExpire(RedisBarcodeStore.KEY_PREFIX + barcode.token(), TimeUnit.MILLISECONDS);
        Long userTtl = redis.getExpire(RedisBarcodeStore.USER_KEY_PREFIX + 7L, TimeUnit.MILLISECONDS);

        assertThat(barcodeTtl).isBetween(RETENTION.minusSeconds(5).toMillis(), RETENTION.toMillis());
        assertThat(userTtl).isBetween(RETENTION.minusSeconds(5).toMillis(), RETENTION.toMillis());
    }

    @Test
    void 다시_발급하면_이전_ISSUED_바코드는_EXPIRED로_바뀐다() {
        Barcode first = issued(7L);
        Barcode second = issued(7L);
        Barcode otherUser = issued(8L);
        store.issue(first, RETENTION);
        store.issue(otherUser, RETENTION);

        store.issue(second, RETENTION);

        assertThat(store.find(first.token())).get().extracting(Barcode::status).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(store.find(second.token())).get().extracting(Barcode::status).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(store.find(otherUser.token())).get().extracting(Barcode::status).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void 결제가_진행_중인_이전_바코드는_그대로_둔다() {
        Barcode scanned = issued(7L);
        store.issue(scanned, RETENTION);
        redis.opsForHash().put(RedisBarcodeStore.KEY_PREFIX + scanned.token(), "status", "SCANNED");

        store.issue(issued(7L), RETENTION);

        assertThat(store.find(scanned.token())).get().extracting(Barcode::status).isEqualTo(BarcodeStatus.SCANNED);
    }

    @Test
    void 같은_토큰이_이미_있으면_저장하지_않는다() {
        Barcode original = issued(7L);
        store.issue(original, RETENTION);
        Barcode sameToken = new Barcode(original.token(), 8L, BarcodeStatus.ISSUED, ISSUED_AT, ISSUED_AT.plusSeconds(60));

        assertThat(store.issue(sameToken, RETENTION)).isFalse();
        assertThat(store.find(original.token())).contains(original);
    }

    @Test
    void 같은_사용자가_동시에_10번_발급해도_ISSUED_바코드는_하나다() throws Exception {
        int requests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                Callable<String> issue = () -> {
                    Barcode barcode = issued(7L);
                    start.await();
                    store.issue(barcode, RETENTION);
                    return barcode.token();
                };
                results.add(executor.submit(issue));
            }
            start.countDown();

            long issuedCount = 0;
            for (Future<String> result : results) {
                String token = result.get(10, TimeUnit.SECONDS);
                if (store.find(token).orElseThrow().status() == BarcodeStatus.ISSUED) {
                    issuedCount++;
                }
            }
            assertThat(issuedCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ───────── 스캔(5.1.3) ─────────

    @Test
    void ISSUED_바코드를_스캔하면_SCANNED와_결제_번호가_기록되고_보관_시간이_늘어난다() {
        Barcode barcode = live(7L);
        store.issue(barcode, Duration.ofMinutes(1));

        assertThat(store.scan(barcode.token(), 1024L, RETENTION)).isEqualTo(BarcodeStore.ScanResult.SCANNED);

        Barcode scanned = store.find(barcode.token()).orElseThrow();
        assertThat(scanned.status()).isEqualTo(BarcodeStatus.SCANNED);
        assertThat(scanned.paymentId()).isEqualTo(1024L);
        assertThat(redis.getExpire("barcode:code:" + barcode.token(), TimeUnit.SECONDS)).isBetween(590L, 600L);
        assertThat(redis.getExpire("barcode:user:7", TimeUnit.SECONDS)).isBetween(590L, 600L);
    }

    @Test
    void 이미_스캔된_바코드는_다시_스캔할_수_없고_결제_번호도_바뀌지_않는다() {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);
        store.scan(barcode.token(), 1024L, RETENTION);

        assertThat(store.scan(barcode.token(), 2048L, RETENTION)).isEqualTo(BarcodeStore.ScanResult.ALREADY_USED);
        assertThat(store.find(barcode.token()).orElseThrow().paymentId()).isEqualTo(1024L);
    }

    @Test
    void 유효_시간이_지난_바코드는_EXPIRED로_바뀌고_스캔되지_않는다() {
        Barcode barcode = issued(7L); // 2026-10-28 발급, 이미 지남
        store.issue(barcode, RETENTION);

        assertThat(store.scan(barcode.token(), 1024L, RETENTION)).isEqualTo(BarcodeStore.ScanResult.EXPIRED);
        assertThat(store.find(barcode.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(store.scan(barcode.token(), 1024L, RETENTION)).isEqualTo(BarcodeStore.ScanResult.EXPIRED);
    }

    @Test
    void 없는_토큰이나_형식이_다른_토큰은_NOT_FOUND() {
        assertThat(store.scan("0000000000000000", 1L, RETENTION)).isEqualTo(BarcodeStore.ScanResult.NOT_FOUND);
        assertThat(store.scan("user:7", 1L, RETENTION)).isEqualTo(BarcodeStore.ScanResult.NOT_FOUND);
    }

    @Test
    void 같은_바코드를_동시에_스캔하면_한_건만_SCANNED다() throws Exception {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);
        int requests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BarcodeStore.ScanResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                long paymentId = 100 + i;
                Callable<BarcodeStore.ScanResult> scan = () -> {
                    start.await();
                    return store.scan(barcode.token(), paymentId, RETENTION);
                };
                results.add(executor.submit(scan));
            }
            start.countDown();
            long scannedCount = 0;
            for (Future<BarcodeStore.ScanResult> result : results) {
                if (result.get(10, TimeUnit.SECONDS) == BarcodeStore.ScanResult.SCANNED) {
                    scannedCount++;
                }
            }
            assertThat(scannedCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 되돌리기는_그_결제_번호로_SCANNED일_때만_ISSUED로_돌린다() {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);
        store.scan(barcode.token(), 1024L, RETENTION);

        assertThat(store.revertScan(barcode.token(), 2048L)).isFalse();
        assertThat(store.revertScan(barcode.token(), 1024L)).isTrue();
        Barcode reverted = store.find(barcode.token()).orElseThrow();
        assertThat(reverted.status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(reverted.paymentId()).isNull();
        assertThat(store.revertScan(barcode.token(), 1024L)).isFalse();
    }

    private Barcode issued(Long userId) {
        return new Barcode(tokens.next(), userId, BarcodeStatus.ISSUED, ISSUED_AT, ISSUED_AT.plusSeconds(60));
    }

    /** 지금 발급돼 아직 유효한 바코드. 스캔은 실제 시각으로 만료를 보므로 ISSUED_AT(과거)을 쓰면 안 된다. */
    private Barcode live(Long userId) {
        Instant now = Instant.now();
        return new Barcode(tokens.next(), userId, BarcodeStatus.ISSUED, now, now.plusSeconds(60));
    }
}
