package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 같은 시리즈를 동시에 고치는 요청이 첫 규칙 행 잠금으로 차례로 처리되는지 실제 MySQL에서 확인한다.
 * 두 트랜잭션이 각자 커밋해야 하므로 테스트 트랜잭션(@Transactional)을 쓰지 않는다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-cashflow-concurrency-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CashflowConcurrencyTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired CashflowService service;

    @Test
    void 같은_회차를_처음_고치는_두_요청이_동시에_와도_둘_다_처리되고_회차_행은_하나다() throws Exception {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        CashflowService.Item rule = service.create(owner, new CashflowService.CreateCommand("헬스장",
                CashflowItem.Direction.EXPENSE, 89000L, tomorrow, CashflowItem.RecurrenceType.WEEKLY,
                null, null, null, account, null, null, null));
        LocalDate target = tomorrow.plusDays(7);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CashflowService.Entry>> results = new ArrayList<>();
        try {
            for (long amount : new long[] {7000L, 8000L}) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.update(owner, rule.id(), new CashflowService.UpdateCommand(null, null, amount,
                            null, null, null, null, null, null, CashflowService.UpdateScope.THIS_ONLY, target));
                }));
            }
            start.countDown();
            for (Future<CashflowService.Entry> result : results) {
                // 잠금 없이 둘 다 회차 행을 만들면 한쪽이 UNIQUE(규칙, 원래 회차) 위반으로 실패한다.
                assertThat(result.get(30, TimeUnit.SECONDS).occurrenceDate()).isEqualTo(target);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForList("""
                SELECT amount FROM cashflow_occurrence WHERE cashflow_item_id = ? AND occurrence_date = ?
                """, Long.class, rule.id(), target)).singleElement().isIn(7000L, 8000L);
    }
}
