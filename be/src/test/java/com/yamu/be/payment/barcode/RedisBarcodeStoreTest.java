package com.yamu.be.payment.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.payment.barcode.Barcode.IssueAuth;
import com.yamu.be.payment.barcode.BarcodeStore.Reissue;
import com.yamu.be.payment.barcode.BarcodeStore.ReissueResult;
import com.yamu.be.support.RedisTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

        assertThat(store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION)).isEqualTo(BarcodeStore.ScanResult.SCANNED);

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
        store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION);

        assertThat(store.scan(barcode.token(), barcode.userId(), 2048L, Instant.now(), RETENTION)).isEqualTo(BarcodeStore.ScanResult.ALREADY_USED);
        assertThat(store.find(barcode.token()).orElseThrow().paymentId()).isEqualTo(1024L);
    }

    @Test
    void 유효_시간이_지난_바코드는_EXPIRED로_바뀌고_스캔되지_않는다() {
        Barcode barcode = issued(7L); // 2026-10-28 발급, 이미 지남
        store.issue(barcode, RETENTION);

        assertThat(store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION)).isEqualTo(BarcodeStore.ScanResult.EXPIRED);
        assertThat(store.find(barcode.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION)).isEqualTo(BarcodeStore.ScanResult.EXPIRED);
    }

    @Test
    void 없는_토큰이나_형식이_다른_토큰은_NOT_FOUND() {
        assertThat(store.scan("0000000000000000", 7L, 1L, Instant.now(), RETENTION)).isEqualTo(BarcodeStore.ScanResult.NOT_FOUND);
        assertThat(store.scan("user:7", 7L, 1L, Instant.now(), RETENTION)).isEqualTo(BarcodeStore.ScanResult.NOT_FOUND);
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
                    return store.scan(barcode.token(), barcode.userId(), paymentId, Instant.now(), RETENTION);
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
        store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION);

        assertThat(store.revertScan(barcode.token(), 2048L)).isFalse();
        assertThat(store.revertScan(barcode.token(), 1024L)).isTrue();
        Barcode reverted = store.find(barcode.token()).orElseThrow();
        assertThat(reverted.status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(reverted.paymentId()).isNull();
        assertThat(store.revertScan(barcode.token(), 1024L)).isFalse();
    }

    // ───────── 승인·만료 반영(5.2.2) ─────────

    @Test
    void 스캔하면_결제_번호로_토큰을_찾는_역색인이_같은_보관_시간으로_생기고_되돌리면_지워진다() {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);

        store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION);

        assertThat(redis.opsForValue().get("barcode:payment:1024")).isEqualTo(barcode.token());
        assertThat(redis.getExpire("barcode:payment:1024", TimeUnit.SECONDS)).isBetween(590L, 600L);

        store.revertScan(barcode.token(), 1024L);
        assertThat(redis.hasKey("barcode:payment:1024")).isFalse();
    }

    @Test
    void 결제가_승인되면_그_결제의_바코드만_USED가_된다() {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);
        store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION);

        assertThat(store.markUsed(2048L)).isFalse();
        assertThat(store.markUsed(1024L)).isTrue();

        Barcode used = store.find(barcode.token()).orElseThrow();
        assertThat(used.status()).isEqualTo(BarcodeStatus.USED);
        assertThat(used.paymentId()).isEqualTo(1024L);
        // 한 번 USED가 되면 다시 바꾸거나 되돌릴 수 없다
        assertThat(store.markUsed(1024L)).isFalse();
        assertThat(store.expireByPayment(1024L)).isFalse();
        assertThat(store.revertScan(barcode.token(), 1024L)).isFalse();
    }

    @Test
    void 결제가_만료되면_SCANNED_바코드가_EXPIRED가_된다() {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);
        store.scan(barcode.token(), barcode.userId(), 1024L, Instant.now(), RETENTION);

        assertThat(store.expireByPayment(1024L)).isTrue();

        assertThat(store.find(barcode.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(store.markUsed(1024L)).isFalse();
    }

    @Test
    void 스캔되지_않은_바코드는_승인_만료_반영_대상이_아니다() {
        Barcode barcode = live(7L);
        store.issue(barcode, RETENTION);

        assertThat(store.markUsed(1024L)).isFalse();
        assertThat(store.expireByPayment(1024L)).isFalse();
        assertThat(store.find(barcode.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.ISSUED);
    }

    @Test
    void POS_취소에_쓴_바코드는_ISSUED일_때만_USED가_되고_취소한_결제가_붙으며_스캔된_바코드는_그대로다() {
        Barcode issued = live(7L);
        store.issue(issued, RETENTION);
        Barcode scanned = live(8L);
        store.issue(scanned, RETENTION);
        store.scan(scanned.token(), scanned.userId(), 1024L, Instant.now(), RETENTION);

        assertThat(store.useForCancel(issued.token(), 2048L)).isTrue();
        assertThat(store.useForCancel(issued.token(), 4096L)).isFalse();
        assertThat(store.useForCancel(scanned.token(), 2048L)).isFalse();
        assertThat(store.useForCancel("9999999999999999", 2048L)).isFalse();
        assertThat(store.useForCancel("user:7", 2048L)).isFalse();

        Barcode used = store.find(issued.token()).orElseThrow();
        assertThat(used.status()).isEqualTo(BarcodeStatus.USED);
        assertThat(used.paymentId()).isEqualTo(2048L);
        Barcode stillScanned = store.find(scanned.token()).orElseThrow();
        assertThat(stillScanned.status()).isEqualTo(BarcodeStatus.SCANNED);
        assertThat(stillScanned.paymentId()).isEqualTo(1024L);
        // 취소에 쓴 바코드에는 결제 번호 역색인을 만들지 않아 승인·만료 반영이 건드리지 않는다
        assertThat(store.markUsed(2048L)).isFalse();
        assertThat(store.expireByPayment(2048L)).isFalse();
    }

    // ───────── 인증 정보 · 재발급 ─────────

    @Test
    void 인증_정보와_재발급한_토큰을_그대로_읽고_해시_칸_이름은_정해진_대로다() {
        // Redis에는 밀리초까지 저장한다
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Barcode first = authed(7L, now);
        store.issue(first, RETENTION);
        assertThat(store.find(first.token())).contains(first);

        Barcode next = successorOf(first, now);
        assertThat(store.reissue(first.token(), next, RETENTION, now).result())
                .isEqualTo(ReissueResult.REISSUED);

        assertThat(redis.opsForHash().entries("barcode:code:" + next.token())).containsOnlyKeys("userId", "status",
                "issuedAt", "expiresAt", "userDeviceId", "authAt", "authMethod", "authAuditLogId");
        assertThat(store.find(next.token())).contains(next);
        Barcode closed = store.find(first.token()).orElseThrow();
        assertThat(closed.status()).isEqualTo(BarcodeStatus.EXPIRED);
        assertThat(closed.reissuedTo()).isEqualTo(next.token());
        assertThat(closed.auth()).isEqualTo(first.auth());
        assertThat(redis.opsForValue().get("barcode:user:7")).isEqualTo(next.token());
        Long nextTtl = redis.getExpire("barcode:code:" + next.token(), TimeUnit.MILLISECONDS);
        assertThat(nextTtl).isBetween(RETENTION.minusSeconds(5).toMillis(), RETENTION.toMillis());
    }

    @Test
    void 시간이_지나_만료된_현재_코드는_재발급하고_스캔_결제_취소에_쓰인_코드는_거절한다() {
        Instant now = Instant.now();
        Barcode stale = authed(7L, now.minusSeconds(120));
        store.issue(stale, RETENTION);
        assertThat(store.reissue(stale.token(), successorOf(stale, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.REISSUED);

        Barcode scanned = authed(8L, now);
        store.issue(scanned, RETENTION);
        store.scan(scanned.token(), 8L, 1024L, now, RETENTION);
        assertThat(store.reissue(scanned.token(), successorOf(scanned, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);
        store.expireByPayment(1024L); // 결제 확정 시간 초과로 닫혀도 결제에 쓰인 코드다
        assertThat(store.reissue(scanned.token(), successorOf(scanned, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);

        Barcode canceled = authed(9L, now);
        store.issue(canceled, RETENTION);
        store.useForCancel(canceled.token(), 2048L);
        assertThat(store.reissue(canceled.token(), successorOf(canceled, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);
        assertThat(redis.opsForValue().get("barcode:user:8")).isEqualTo(scanned.token());
        assertThat(redis.opsForValue().get("barcode:user:9")).isEqualTo(canceled.token());
    }

    @Test
    void 현재_코드가_아니거나_없거나_형식이_다르면_거절한다() {
        Instant now = Instant.now();
        Barcode first = authed(7L, now);
        Barcode second = authed(7L, now);
        store.issue(first, RETENTION);
        store.issue(second, RETENTION);

        assertThat(store.reissue(first.token(), successorOf(first, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);
        assertThat(store.reissue("9999999999999999", successorOf(first, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);
        assertThat(store.reissue("user:7", successorOf(first, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);
        assertThat(redis.opsForValue().get("barcode:user:7")).isEqualTo(second.token());
    }

    @Test
    void 새_토큰이_이미_있으면_아무것도_바꾸지_않고_TOKEN_TAKEN이다() {
        Instant now = Instant.now();
        Barcode first = authed(7L, now);
        Barcode someone = authed(8L, now);
        store.issue(first, RETENTION);
        store.issue(someone, RETENTION);
        Barcode clash = new Barcode(someone.token(), 7L, BarcodeStatus.ISSUED, now, now.plusSeconds(60), null,
                first.auth(), null);

        assertThat(store.reissue(first.token(), clash, RETENTION, now).result()).isEqualTo(ReissueResult.TOKEN_TAKEN);
        assertThat(store.find(first.token()).orElseThrow().status()).isEqualTo(BarcodeStatus.ISSUED);
        assertThat(store.find(someone.token()).orElseThrow().userId()).isEqualTo(8L);
    }

    @Test
    void 같은_이전_코드로_다시_오면_새_코드가_쓸_수_있을_때만_그_토큰을_돌려준다() {
        Instant now = Instant.now();
        Barcode first = authed(7L, now);
        store.issue(first, RETENTION);
        Barcode next = successorOf(first, now);
        store.reissue(first.token(), next, RETENTION, now);

        Reissue again = store.reissue(first.token(), successorOf(first, now), RETENTION, now);
        assertThat(again).isEqualTo(new Reissue(ReissueResult.ALREADY_REISSUED, next.token()));
        // 새 코드가 만료되면 이전 코드로는 받을 수 없다
        assertThat(store.reissue(first.token(), successorOf(first, now), RETENTION, now.plusSeconds(61)).result())
                .isEqualTo(ReissueResult.DENIED);
        // 새 코드가 스캔되면 마찬가지다
        store.scan(next.token(), 7L, 1024L, now, RETENTION);
        assertThat(store.reissue(first.token(), successorOf(first, now), RETENTION, now).result())
                .isEqualTo(ReissueResult.DENIED);
    }

    @Test
    void 같은_코드를_동시에_재발급하면_하나만_저장되고_나머지는_그_토큰을_받는다() throws Exception {
        Instant now = Instant.now();
        Barcode first = authed(7L, now);
        store.issue(first, RETENTION);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Reissue>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Barcode next = successorOf(first, now);
                Callable<Reissue> call = () -> {
                    start.await();
                    return store.reissue(first.token(), next, RETENTION, now);
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Reissue> done = new ArrayList<>();
            for (Future<Reissue> f : results) {
                done.add(f.get(5, TimeUnit.SECONDS));
            }

            assertThat(done).filteredOn(r -> r.result() == ReissueResult.REISSUED).hasSize(1);
            String winner = done.stream().filter(r -> r.result() == ReissueResult.REISSUED).findFirst().orElseThrow()
                    .token();
            assertThat(done).filteredOn(r -> r.result() != ReissueResult.REISSUED)
                    .allSatisfy(r -> assertThat(r).isEqualTo(new Reissue(ReissueResult.ALREADY_REISSUED, winner)));
            assertThat(redis.keys("barcode:code:*")).hasSize(2);
            assertThat(redis.opsForValue().get("barcode:user:7")).isEqualTo(winner);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 재발급과_스캔이_겹치면_둘_중_하나만_된다() throws Exception {
        for (int round = 0; round < 20; round++) {
            long paymentId = 5000L + round;
            Instant now = Instant.now();
            Barcode first = authed(7L, now);
            store.issue(first, RETENTION);
            Barcode next = successorOf(first, now);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Reissue> reissue = pool.submit(() -> {
                    start.await();
                    return store.reissue(first.token(), next, RETENTION, now);
                });
                Future<BarcodeStore.ScanResult> scan = pool.submit(() -> {
                    start.await();
                    return store.scan(first.token(), 7L, paymentId, now, RETENTION);
                });
                start.countDown();
                boolean reissued = reissue.get(5, TimeUnit.SECONDS).result() == ReissueResult.REISSUED;
                boolean scanned = scan.get(5, TimeUnit.SECONDS) == BarcodeStore.ScanResult.SCANNED;

                assertThat(reissued).isNotEqualTo(scanned);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** 지금 PIN으로 인증해 연 코드 */
    private Barcode authed(Long userId, Instant issuedAt) {
        return new Barcode(tokens.next(), userId, BarcodeStatus.ISSUED, issuedAt, issuedAt.plusSeconds(60), null,
                new IssueAuth(userId * 10, issuedAt, AuthMethod.PIN, 900L), null);
    }

    /** 이전 코드의 인증 정보를 물려받은 새 코드 */
    private Barcode successorOf(Barcode previous, Instant issuedAt) {
        return new Barcode(tokens.next(), previous.userId(), BarcodeStatus.ISSUED, issuedAt, issuedAt.plusSeconds(60),
                null, previous.auth(), null);
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
