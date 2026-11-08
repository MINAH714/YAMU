package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
 * 같은 결제나 같은 회차를 동시에 연결하는 요청 중 하나만 성공하고 나머지는 409인지 실제 MySQL에서 확인한다(S15P31E103-319).
 * 두 트랜잭션이 각자 커밋해야 하므로 테스트 트랜잭션(@Transactional)을 쓰지 않는다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-payment-match-concurrency-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentMatchConcurrencyTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentMatchService service;

    @Test
    void 다른_예정_지출이_같은_결제를_동시에_연결하면_하나만_성공하고_나머지는_409다() throws Exception {
        Setup setup = setup();
        long otherRule = setup.cashflows.cardRule(setup.owner, setup.card, setup.day, "NONE", 30000L, null);
        long payment = setup.payment();

        List<ErrorCode> failures = race(
                () -> service.confirm(setup.owner, setup.rule, setup.day, payment),
                () -> service.confirm(setup.owner, otherRule, setup.day, payment));

        // 결제는 잠그지 않으므로 늦은 쪽은 유일 키(uk_match_payment)에 막힌다. 500이면 실패다.
        assertThat(failures).containsExactly(ErrorCode.CASHFLOW_MATCH_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match WHERE payment_transaction_id = ?",
                Integer.class, payment)).isEqualTo(1);
    }

    @Test
    void 같은_회차에_다른_결제를_동시에_연결하면_하나만_성공하고_나머지는_409다() throws Exception {
        Setup setup = setup();
        long first = setup.payment();
        long second = setup.payment();

        List<ErrorCode> failures = race(
                () -> service.confirm(setup.owner, setup.rule, setup.day, first),
                () -> service.confirm(setup.owner, setup.rule, setup.day, second));

        assertThat(failures).containsExactly(ErrorCode.CASHFLOW_MATCH_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_payment_match m JOIN cashflow_occurrence o "
                + "ON o.cashflow_occurrence_id = m.cashflow_occurrence_id WHERE o.cashflow_item_id = ?",
                Integer.class, setup.rule)).isEqualTo(1);
    }

    @Test
    void 거절된_매칭이_만든_회차_행은_함께_되돌아간다() {
        Setup setup = setup();
        long weekly = setup.cashflows.cardRule(setup.owner, setup.card, setup.day, "WEEKLY", 30000L, null);
        long payment = setup.payment();
        service.confirm(setup.owner, setup.rule, setup.day, payment);

        // 둘째 회차는 저장된 행이 없어 매칭 확정이 만들지만, 결제가 이미 연결돼 409로 끝난다.
        assertThatThrownBy(() -> service.confirm(setup.owner, weekly, setup.day.plusDays(7), payment))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CASHFLOW_MATCH_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE cashflow_item_id = ? "
                + "AND occurrence_date = ?", Integer.class, weekly, setup.day.plusDays(7))).isZero();
    }

    /** 두 요청을 동시에 보내고 실패한 요청의 에러 코드를 돌려준다. BusinessException이 아닌 실패는 그대로 던진다. */
    private static List<ErrorCode> race(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        List<ErrorCode> failures = new ArrayList<>();
        try {
            for (Callable<?> call : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof BusinessException business)) {
                        throw e;
                    }
                    failures.add(business.getErrorCode());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    private Setup setup() {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        CashflowFixtures cashflows = new CashflowFixtures(jdbc);
        long owner = ownership.user();
        long card = ownership.card(owner, ownership.product(), ownership.account(owner), "ACTIVE");
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        long rule = cashflows.cardRule(owner, card, day, "NONE", 30000L, null);
        return new Setup(cashflows, owner, card, rule, day, cashflows.merchant("SPORTS"));
    }

    private record Setup(CashflowFixtures cashflows, long owner, long card, long rule, LocalDate day, long merchant) {
        long payment() {
            return cashflows.payment(owner, card, merchant, 30000L, day, "YAMU", "APPROVED");
        }
    }
}
