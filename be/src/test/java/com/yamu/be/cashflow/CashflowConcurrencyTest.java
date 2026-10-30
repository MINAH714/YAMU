package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
                            null, null, null, null, null, null, null, CashflowService.UpdateScope.THIS_ONLY, target));
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

    @Test
    void 나뉜_시리즈의_앞_규칙_수정과_뒤_규칙_삭제가_동시에_와도_차례로_처리되고_회차가_겹치지_않는다() throws Exception {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        long first = new CashflowFixtures(jdbc).rule(owner, account, today.minusDays(14), "WEEKLY", null);
        long second = service.update(owner, first, new CashflowService.UpdateCommand(null, null, 20000L, null, null,
                null, null, null, null, null, CashflowService.UpdateScope.THIS_AND_FUTURE, today.plusDays(14))).item().id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            results.add(pool.submit(() -> {
                start.await();
                return service.update(owner, first, new CashflowService.UpdateCommand(null, null, 30000L, null, null,
                        null, null, null, null, null, CashflowService.UpdateScope.THIS_AND_FUTURE, today.plusDays(7)));
            }));
            results.add(pool.submit(() -> {
                start.await();
                service.delete(owner, second, CashflowService.DeleteScope.ALL, null);
                return null;
            }));
            start.countDown();
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // 어느 쪽이 먼저 잠그느냐에 따라 뒤 요청은 거절된다. 수정이 먼저면 삭제할 규칙이 사라져 404,
                    // 삭제가 먼저면 앞 규칙이 어제로 닫혀 고를 회차가 없어 400이다. 교착·제약 위반(500)은 실패다.
                    assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) e.getCause()).getErrorCode())
                            .isIn(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.INVALID_REQUEST);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<LocalDate> dates = service.list(owner, today, today.plusDays(28), null, null, true).stream()
                .map(CashflowService.Entry::date).toList();
        assertThat(dates).doesNotHaveDuplicates();
    }
}
