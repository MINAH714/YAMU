package com.yamu.be.payment.billing.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferResult.RejectReason;
import com.yamu.be.payment.bank.TransferResult.Status;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.billing.BillFixtures;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.withdraw.BillWithdrawer.Kind;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 결제일 청구 출금(Task 5.4.3)을 실제 MySQL·Redis·영업일 달력(내장 공휴일)으로 확인한다. 금융망(SsafyTransferApi)만 가짜 은행으로
 * 바꾼다. 가짜 은행은 출금이 처리되면 거래내역에 태그 한 줄을 적고, 태그 조회는 그 거래내역을 읽는다.
 *
 * <p>시계는 옮길 수 있는 테스트 시계(MutableClock)라 결과 불명의 유예(approve.recovery-grace 30초)를 기다리지 않고 넘긴다.
 * 출금 예정일은 오늘(한국) 이전의 가장 가까운 영업일로 둔다.
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "spring.application.name=yamu-bill-withdraw-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillWithdrawIntegrationTest extends MySqlTestSupport {

    static final String ADMIN_KEY = "test-admin-key";
    static final String ACCOUNT_NO = "0012345678901234";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final YearMonth MONTH = YearMonth.of(2026, 10);
    static final long BALANCE = 1_000_000;
    static final Duration PAST_GRACE = Duration.ofSeconds(31);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @MockitoBean SsafyTransferApi transfer;

    @Autowired MockMvc mockMvc;
    @Autowired BillWithdrawJob job;
    @Autowired BillWithdrawer withdrawer;
    @Autowired CardBillRepository billRepository;
    @Autowired BusinessDayCalendar calendar;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PersonalDataCipher cipher;
    @Autowired MutableClock clock;
    @Autowired ApplicationEvents events;

    /** 가짜 은행의 거래내역(출금 계좌 하나) */
    final List<BankTransaction> bank = new CopyOnWriteArrayList<>();
    /** 출금 요청마다 차례로 돌려줄 결과. 비면 성공(거래내역에 한 줄) */
    final Deque<Supplier<TransferResult>> answers = new ArrayDeque<>();
    final List<String> requestNos = new CopyOnWriteArrayList<>();
    final AtomicInteger sequence = new AtomicInteger();
    OwnershipFixtures owners;
    BillFixtures fixtures;
    long user;
    long account;
    long card;
    long merchant;
    LocalDate withdrawalDate;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM refund");
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        bank.clear();
        answers.clear();
        requestNos.clear();
        sequence.set(0);
        owners = new OwnershipFixtures(jdbc);
        fixtures = new BillFixtures(jdbc);
        user = ssafyUser();
        account = account(user);
        card = owners.card(user, owners.product(), account, "ACTIVE");
        merchant = fixtures.merchant("출금 테스트 가맹점");
        withdrawalDate = latestBusinessDayBefore(today());

        doAnswer(inv -> nextTransactionNo()).when(transfer).newTransactionNo();
        doAnswer(inv -> matching(inv.getArgument(2))).when(transfer)
                .findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                        any(LocalDate.class));
        doAnswer(inv -> {
            requestNos.add(inv.getArgument(4));
            Supplier<TransferResult> next;
            synchronized (answers) {
                next = answers.poll();
            }
            if (next != null) {
                return next.get();
            }
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).withdraw(anyString(), anyString(), anyLong(), any(TransferTag.class), anyString());
    }

    // ───────── 출금 성공 ─────────

    @Test
    void 출금일이_지난_확정_청구서를_YAMU_B로_확정_당시_계좌에서_출금하고_PAID와_회차_PAID로_바꾼다() {
        Bill bill = confirmed(30_000, 12_000);

        JobRunReport report = runNow();

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.SUCCEEDED);
            assertThat(item.result()).isEqualTo("PAID");
            assertThat(item.amount()).isEqualTo(42_000);
            assertThat(item.finTransactionNo()).isEqualTo("B1");
        });
        verify(transfer).withdraw(eq("user-key-" + user), eq(ACCOUNT_NO), eq(42_000L), eq(TransferTag.bill(bill.id())),
                anyString());
        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(((Number) row.get("paid_amount")).longValue()).isEqualTo(42_000);
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(row.get("fin_transaction_no")).isEqualTo("B1");
        assertThat(row.get("fin_request_no")).isEqualTo(requestNos.get(0));
        assertThat(row.get("withdrawal_retried_at")).isNull();
        assertThat(bill.items()).allSatisfy(item -> assertThat(itemStatus(item)).isEqualTo("PAID"));
        assertThat(events.stream(BillWithdrawnEvent.class)).containsExactly(
                new BillWithdrawnEvent(bill.id(), user, card, 42_000, today(), "B1"));
        // 출금 전 태그는 출금 예정일 전날부터 오늘까지 찾는다
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(transfer).findAllByTag(anyString(), anyString(), eq(TransferTag.bill(bill.id())), from.capture(),
                eq(today()));
        assertThat(from.getValue()).isEqualTo(withdrawalDate.minusDays(1));
    }

    @Test
    void 출금_예정일_전이거나_확정_전_출금_완료_미납_청구서는_대상이_아니다() {
        long future = fixtures.confirmed(user, card, MONTH, today().plusDays(1), account, 10_000);
        item(future, 10_000, "BILLED");
        long accumulating = fixtures.accumulating(user, card, MONTH.minusMonths(1), withdrawalDate);
        fixtures.paid(user, card, MONTH.minusMonths(2), withdrawalDate, account, 5_000, "OLD");
        fixtures.unpaid(user, card, MONTH.minusMonths(3), withdrawalDate, account, 5_000, 0);

        JobRunReport report = runNow();

        assertThat(report.targets()).isZero();
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(future).get("status")).isEqualTo("CONFIRMED");
        assertThat(billRow(accumulating).get("status")).isEqualTo("ACCUMULATING");
    }

    @Test
    void 서버가_멈췄던_날의_청구서도_다음_실행에_따라잡아_출금한다() {
        Bill old = confirmed(MONTH.minusMonths(1), withdrawalDate.minusMonths(1), 7_000);
        Bill current = confirmed(5_000);

        JobRunReport report = runNow();

        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(2);
        assertThat(billRow(old.id()).get("status")).isEqualTo("PAID");
        assertThat(billRow(current.id()).get("status")).isEqualTo("PAID");
    }

    @Test
    void 앱에서_연결을_끊은_계좌여도_확정_당시_계좌에서_출금한다() {
        Bill bill = confirmed(20_000);
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', disconnected_at = ? WHERE linked_account_id = ?",
                LocalDateTime.now(ZoneOffset.UTC), account);

        assertThat(withdraw(bill.id()).kind()).isEqualTo(Kind.PAID);
        verify(transfer).withdraw(anyString(), eq(ACCOUNT_NO), eq(20_000L), any(), anyString());
    }

    // ───────── 미납 ─────────

    @Test
    void 잔액이_부족하면_UNPAID로_바꾸고_다시_돌려도_자동으로_재출금하지_않으며_그_카드는_미납_카드가_된다() {
        Bill bill = confirmed(30_000);
        answers.add(() -> rejected(RejectReason.INSUFFICIENT_BALANCE, "A1014"));

        JobRunReport report = runNow();

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.SUCCEEDED);
            assertThat(item.result()).isEqualTo("UNPAID");
            assertThat(item.amount()).isEqualTo(30_000);
        });
        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("UNPAID");
        assertThat(row.get("failed_at")).isNotNull();
        assertThat(((Number) row.get("paid_amount")).longValue()).isZero();
        assertThat(itemStatus(bill.items().get(0))).isEqualTo("BILLED");
        assertThat(events.stream(BillUnpaidEvent.class)).containsExactly(
                new BillUnpaidEvent(bill.id(), user, card, 30_000, RejectReason.INSUFFICIENT_BALANCE));
        // 승인(YamuCreditCardApprover)·추천이 미납 카드를 거르는 조건
        assertThat(billRepository.existsByUserCardIdAndStatus(card, BillStatus.UNPAID)).isTrue();

        clock.advance(Duration.ofDays(1));
        JobRunReport again = runNow();
        job.recoverInFlight();

        assertThat(again.targets()).isZero();
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("status")).isEqualTo("UNPAID");
    }

    @Test
    void 계좌가_없다는_거절도_미납이다() {
        Bill bill = confirmed(30_000);
        answers.add(() -> rejected(RejectReason.ACCOUNT_NOT_FOUND, "A1003"));

        assertThat(withdraw(bill.id()).kind()).isEqualTo(Kind.UNPAID);
        assertThat(billRow(bill.id()).get("status")).isEqualTo("UNPAID");
        assertThat(events.stream(BillUnpaidEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.reason()).isEqualTo(RejectReason.ACCOUNT_NOT_FOUND));
    }

    // ───────── 결과 불명·재시도 ─────────

    @Test
    void 결과_불명이면_CONFIRMED로_두고_유예_안에는_다시_보내지_않으며_유예_뒤_거래내역에_있으면_PAID로_확정한다() {
        Bill bill = confirmed(30_000);
        answers.add(() -> {
            land(TransferTag.bill(bill.id()), 30_000);  // 은행은 처리했지만 응답을 받지 못함(타임아웃)
            return unknown();
        });

        JobRunReport first = runNow();

        assertThat(first.results()).singleElement().satisfies(item -> {
            assertThat(item.result()).isEqualTo("UNKNOWN");
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.SKIPPED);
        });
        assertThat(billRow(bill.id()).get("status")).isEqualTo("CONFIRMED");
        assertThat(billRow(bill.id()).get("fin_request_no")).isEqualTo(requestNos.get(0));
        assertThat(events.stream(BillWithdrawnEvent.class)).isEmpty();

        // 유예 안: 수동 재실행도 결과 불명 복구도 보내지 않는다
        assertThat(job.recoverInFlight().results()).singleElement()
                .satisfies(item -> assertThat(item.result()).isEqualTo("IN_FLIGHT"));
        assertThat(runNow().results()).singleElement()
                .satisfies(item -> assertThat(item.result()).isEqualTo("IN_FLIGHT"));

        clock.advance(PAST_GRACE);
        JobRunReport recovered = job.recoverInFlight();

        assertThat(recovered.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("status")).isEqualTo("PAID");
        assertThat(billRow(bill.id()).get("withdrawal_retried_at")).isNull();
        assertThat(events.stream(BillWithdrawnEvent.class)).hasSize(1);
    }

    @Test
    void 타임아웃_뒤_유예가_지나도_거래내역에_없으면_새_번호로_한_번_다시_보낸다() {
        Bill bill = confirmed(30_000);
        answers.add(BillWithdrawIntegrationTest::unknown);  // 은행도 처리하지 않음

        runNow();
        clock.advance(PAST_GRACE);
        JobRunReport recovered = job.recoverInFlight();

        assertThat(recovered.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
        verify(transfer, times(2)).withdraw(anyString(), anyString(), eq(30_000L), eq(TransferTag.bill(bill.id())),
                anyString());
        assertThat(requestNos).hasSize(2).doesNotHaveDuplicates();
        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("fin_request_no")).isEqualTo(requestNos.get(1));
        assertThat(row.get("withdrawal_retried_at")).isNotNull();
        assertThat(bank).hasSize(1);
    }

    @Test
    void 다시_보낸_출금도_확인되지_않으면_더_보내지_않고_CONFIRMED로_두어_운영이_확인하며_error_로그는_한_번만_남긴다(
            CapturedOutput output) {
        Bill bill = confirmed(30_000);
        answers.add(BillWithdrawIntegrationTest::unknown);
        answers.add(BillWithdrawIntegrationTest::unknown);

        runNow();
        clock.advance(PAST_GRACE);
        job.recoverInFlight();  // 한 번 다시 보냄
        clock.advance(PAST_GRACE);
        JobRunReport gaveUp = job.recoverInFlight();
        clock.advance(Duration.ofMinutes(10));
        JobRunReport next = job.recoverInFlight();
        clock.advance(Duration.ofDays(1));
        runNow();
        job.recoverInFlight();

        assertThat(gaveUp.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.FAILED);
            assertThat(item.message()).contains("운영 확인");
        });
        // 정기 작업 결과에는 매번 운영 확인으로 나오지만 error 로그는 처음 한 번만(!139 리뷰)
        assertThat(next.results()).singleElement()
                .satisfies(item -> assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.FAILED));
        assertThat(StringUtils.countOccurrencesOf(output.getAll(),
                "청구 출금을 자동으로 판단하지 않음, CONFIRMED 유지 운영 확인 cardBillId=" + bill.id() + " ")).isEqualTo(1);
        assertThat(output.getAll()).contains("운영 확인으로 알린 청구서라 다시 알리지 않음 cardBillId=" + bill.id());
        verify(transfer, times(2)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("withdrawal_retried_at")).isNotNull();
        assertThat(row.get("failed_at")).isNull();
    }

    @Test
    void 다시_보낸_출금이_거절됐는데_앞_요청이_늦게_나갔으면_미납이_아니라_PAID다() {
        Bill bill = confirmed(30_000);
        TransferTag tag = TransferTag.bill(bill.id());
        answers.add(BillWithdrawIntegrationTest::unknown);  // 첫 요청: 응답을 못 받았고 확인 때도 아직 처리 전
        answers.add(() -> {
            land(tag, 30_000);  // 은행이 첫 요청을 늦게 처리해 잔액을 가져갔고
            return rejected(RejectReason.INSUFFICIENT_BALANCE, "A1014");  // 그래서 재시도는 잔액 부족으로 거절
        });

        runNow();
        clock.advance(PAST_GRACE);
        JobRunReport recovered = job.recoverInFlight();

        assertThat(recovered.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
        verify(transfer, times(2)).withdraw(anyString(), anyString(), anyLong(), eq(tag), anyString());
        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("failed_at")).isNull();
        assertThat(events.stream(BillUnpaidEvent.class)).isEmpty();
        assertThat(events.stream(BillWithdrawnEvent.class)).hasSize(1);
        assertThat(bank).hasSize(1);
    }

    @Test
    void 다시_보낸_출금이_거절되고_앞_요청의_출금도_없으면_미납이다() {
        Bill bill = confirmed(30_000);
        answers.add(BillWithdrawIntegrationTest::unknown);
        answers.add(() -> rejected(RejectReason.INSUFFICIENT_BALANCE, "A1014"));

        runNow();
        clock.advance(PAST_GRACE);
        JobRunReport recovered = job.recoverInFlight();

        assertThat(recovered.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("UNPAID"));
        // 첫 실행·복구 전·거절 뒤 한 번 더
        verify(transfer, times(3)).findAllByTag(anyString(), anyString(), eq(TransferTag.bill(bill.id())),
                any(LocalDate.class), any(LocalDate.class));
        assertThat(billRow(bill.id()).get("status")).isEqualTo("UNPAID");
        assertThat(events.stream(BillUnpaidEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.reason()).isEqualTo(RejectReason.INSUFFICIENT_BALANCE));
    }

    @Test
    void 다시_보낸_출금이_거절된_뒤_거래내역을_확인하지_못하면_미납으로_정하지_않고_운영이_확인한다() {
        Bill bill = confirmed(30_000);
        answers.add(BillWithdrawIntegrationTest::unknown);
        answers.add(() -> rejected(RejectReason.INSUFFICIENT_BALANCE, "A1014"));
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(inv -> {
            if (lookups.incrementAndGet() == 3) {  // 거절 뒤 확인만 실패
                throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
            }
            return matching(inv.getArgument(2));
        }).when(transfer).findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                any(LocalDate.class));

        runNow();
        clock.advance(PAST_GRACE);
        JobRunReport recovered = job.recoverInFlight();

        assertThat(recovered.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.FAILED);
            assertThat(item.message()).contains("운영 확인");
        });
        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("failed_at")).isNull();
        assertThat(events.stream(BillUnpaidEvent.class)).isEmpty();

        // 다음 복구는 재시도까지 한 청구서로 보고 더 보내지 않는다
        clock.advance(PAST_GRACE);
        job.recoverInFlight();
        verify(transfer, times(2)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 다시_보낸_출금이_늦게_처리되면_다음_확인에서_PAID로_바로잡는다() {
        Bill bill = confirmed(30_000);
        answers.add(BillWithdrawIntegrationTest::unknown);
        answers.add(BillWithdrawIntegrationTest::unknown);

        runNow();
        clock.advance(PAST_GRACE);
        job.recoverInFlight();
        land(TransferTag.bill(bill.id()), 30_000);  // 은행이 두 번째 요청을 늦게 처리
        clock.advance(PAST_GRACE);

        assertThat(job.recoverInFlight().results()).singleElement()
                .satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
        assertThat(billRow(bill.id()).get("status")).isEqualTo("PAID");
    }

    @Test
    void 출금은_됐는데_결과를_저장하지_못했어도_재실행이_거래내역을_보고_다시_보내지_않는다() {
        Bill bill = confirmed(30_000);
        land(TransferTag.bill(bill.id()), 30_000);  // 앞선 실행이 출금한 뒤 DB 저장 전에 멈춤(요청 번호도 잃음)

        JobRunReport report = runNow();

        assertThat(report.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        verify(transfer, never()).newTransactionNo();
        assertThat(billRow(bill.id()).get("status")).isEqualTo("PAID");
    }

    @Test
    void 요청이_금융망에_가지_않으면_번호를_지우고_다음_실행에_새_번호로_보낸다() {
        Bill bill = confirmed(30_000);
        answers.add(BillWithdrawIntegrationTest::notSent);

        JobRunReport first = runNow();

        assertThat(first.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("NOT_SENT"));
        assertThat(billRow(bill.id()).get("fin_request_no")).isNull();

        JobRunReport second = runNow();

        assertThat(second.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
        assertThat(requestNos).hasSize(2).doesNotHaveDuplicates();
        assertThat(billRow(bill.id()).get("withdrawal_retried_at")).isNull();
    }

    @Test
    void 같은_청구서를_동시에_출금해도_한_번만_보낸다() throws Exception {
        Bill bill = confirmed(30_000);
        answers.add(() -> {
            sleep(300);  // 다른 실행이 선점 뒤에 끼어들 틈
            land(TransferTag.bill(bill.id()), 30_000);
            return success();
        });

        List<BillWithdrawer.Result> results = Concurrently.run(4, () -> withdraw(bill.id()));

        verify(transfer, times(1)).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(results).extracting(BillWithdrawer.Result::kind).containsOnlyOnce(Kind.PAID)
                .doesNotContain(Kind.UNPAID, Kind.NEEDS_REVIEW);
        assertThat(bank).hasSize(1);
        assertThat(billRow(bill.id()).get("status")).isEqualTo("PAID");
        // 이벤트 기록은 테스트 스레드 것만 보이므로 여기서는 세지 않는다(한 번만 PAID로 바뀐 것으로 확인)
    }

    @Test
    void 같은_작업이_돌고_있으면_수동_실행은_409이고_보내지_않는다() throws Exception {
        Bill bill = confirmed(30_000);
        redis.opsForValue().set(JobLocks.lockKey(BillWithdrawJob.NAME), "other-server");

        mockMvc.perform(post("/api/admin/jobs/bill-withdraw/run").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_ALREADY_RUNNING"));

        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("status")).isEqualTo("CONFIRMED");
    }

    // ───────── 판단하지 않는 경우 ─────────

    @Test
    void 같은_태그의_출금이_두_건이면_보내지_않고_CONFIRMED로_두어_운영이_확인한다() {
        Bill bill = confirmed(30_000);
        land(TransferTag.bill(bill.id()), 30_000);
        land(TransferTag.bill(bill.id()), 30_000);

        JobRunReport report = runNow();

        assertThat(report.results()).singleElement()
                .satisfies(item -> assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.FAILED));
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 태그는_있는데_금액이_다르면_보내지_않고_운영이_확인한다() {
        Bill bill = confirmed(30_000);
        land(TransferTag.bill(bill.id()), 29_000);

        assertThat(withdraw(bill.id()).kind()).isEqualTo(Kind.NEEDS_REVIEW);
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 거래내역_조회가_실패하면_아무것도_바꾸지_않고_다른_청구서는_계속한다() {
        Bill broken = confirmed(MONTH.minusMonths(1), withdrawalDate, 7_000);
        Bill fine = confirmed(5_000);
        doAnswer(inv -> {
            TransferTag tag = inv.getArgument(2);
            if (tag.equals(TransferTag.bill(broken.id()))) {
                throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
            }
            return matching(tag);
        }).when(transfer).findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                any(LocalDate.class));

        JobRunReport report = runNow();

        assertThat(report.count(JobRunReport.Outcome.FAILED)).isEqualTo(1);
        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(1);
        assertThat(billRow(broken.id()).get("status")).isEqualTo("CONFIRMED");
        assertThat(billRow(broken.id()).get("fin_request_no")).isNull();
        assertThat(billRow(fine.id()).get("status")).isEqualTo("PAID");
    }

    @Test
    void 채번에_실패하면_보내지_않고_다음_실행에_다시_본다() {
        Bill bill = confirmed(30_000);
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE)).when(transfer).newTransactionNo();

        assertThat(withdraw(bill.id()).kind()).isEqualTo(Kind.NOT_SENT);
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(billRow(bill.id()).get("fin_request_no")).isNull();
    }

    // ───────── 출금 전 취소한 회차의 환급 ─────────

    @Test
    void 출금_전에_취소한_회차의_환급은_출금이_성공한_뒤에야_금액과_기한이_정해진다() {
        Bill bill = confirmed(30_000);
        long canceledPayment = fixtures.payment(user, card, merchant, 12_000, 1, LocalDateTime.now(ZoneOffset.UTC));
        long canceledItem = fixtures.item(canceledPayment, user, card, bill.id(), 1, MONTH, 12_000, "REFUND_PENDING");
        long refund = fixtures.refund(canceledPayment, canceledItem, user, account, 12_000, "PENDING");
        jdbc.update("UPDATE card_bill SET confirmed_amount = 42000 WHERE card_bill_id = ?", bill.id());
        answers.add(BillWithdrawIntegrationTest::unknown);

        runNow();
        // 결과 불명 동안 환급은 기한 없는 대기 그대로다(입금 대상 아님)
        assertThat(refundRow(refund).get("due_date")).isNull();

        land(TransferTag.bill(bill.id()), 42_000);
        clock.advance(PAST_GRACE);
        job.recoverInFlight();

        Map<String, Object> row = refundRow(refund);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((java.sql.Date) row.get("due_date")).toLocalDate())
                .isEqualTo(calendar.plusBusinessDays(today(), 3));
        assertThat(((Number) row.get("refund_amount")).longValue()).isEqualTo(12_000);
        assertThat(itemStatus(bill.items().get(0))).isEqualTo("PAID");
        assertThat(itemStatus(canceledItem)).isEqualTo("REFUND_PENDING");
    }

    @Test
    void 출금이_거절되면_출금_전에_취소한_회차의_환급은_무효가_되고_그_몫은_미납에서_빠진다() {
        Bill bill = confirmed(30_000);
        long canceledPayment = fixtures.payment(user, card, merchant, 12_000, 1, LocalDateTime.now(ZoneOffset.UTC));
        long canceledItem = fixtures.item(canceledPayment, user, card, bill.id(), 1, MONTH, 12_000, "REFUND_PENDING");
        long refund = fixtures.refund(canceledPayment, canceledItem, user, account, 12_000, "PENDING");
        jdbc.update("UPDATE card_bill SET confirmed_amount = 42000 WHERE card_bill_id = ?", bill.id());
        answers.add(() -> rejected(RejectReason.INSUFFICIENT_BALANCE, "A1014"));

        runNow();

        Map<String, Object> row = billRow(bill.id());
        assertThat(row.get("status")).isEqualTo("UNPAID");
        assertThat(((Number) row.get("canceled_unpaid_principal")).longValue()).isEqualTo(12_000);
        assertThat(refundRow(refund).get("status")).isEqualTo("CANCELED");
        assertThat(itemStatus(canceledItem)).isEqualTo("EXCLUDED");
        assertThat(events.stream(BillUnpaidEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.unpaidAmount()).isEqualTo(30_000));
    }

    @Test
    void 청구서의_결제를_모두_출금_전에_취소했으면_거절_뒤_CANCELED이고_미납_알림은_없다() {
        long bill = fixtures.confirmed(user, card, MONTH, withdrawalDate, account, 12_000);
        long payment = fixtures.payment(user, card, merchant, 12_000, 1, LocalDateTime.now(ZoneOffset.UTC));
        long item = fixtures.item(payment, user, card, bill, 1, MONTH, 12_000, "REFUND_PENDING");
        fixtures.refund(payment, item, user, account, 12_000, "PENDING");
        answers.add(() -> rejected(RejectReason.INSUFFICIENT_BALANCE, "A1014"));

        JobRunReport report = runNow();

        assertThat(report.results()).singleElement().satisfies(r -> assertThat(r.result()).isEqualTo("CANCELED"));
        assertThat(billRow(bill).get("status")).isEqualTo("CANCELED");
        assertThat(events.stream(BillUnpaidEvent.class)).isEmpty();
        assertThat(billRepository.existsByUserCardIdAndStatus(card, BillStatus.UNPAID)).isFalse();
    }

    // ───────── 영업일 보정 ─────────

    @Test
    void 출금_예정일이_확정_뒤_공휴일이_되면_출금하지_않고_다음_영업일로_미룬다() {
        // 확정 뒤 공휴일 발표를 흉내: 비영업일(지난 토요일)을 출금 예정일로 저장한 청구서
        LocalDate saturday = today().minusDays(3).with(java.time.temporal.TemporalAdjusters.previousOrSame(
                DayOfWeek.SATURDAY));
        Bill bill = confirmed(MONTH, saturday, 30_000);
        LocalDate next = calendar.businessDayOnOrAfter(saturday);

        JobRunReport onHoliday = run(saturday.atTime(7, 0).atZone(KST).toInstant());

        assertThat(onHoliday.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("POSTPONED"));
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(((java.sql.Date) billRow(bill.id()).get("withdrawal_date")).toLocalDate()).isEqualTo(next);

        assumeThat(next).isBeforeOrEqualTo(today());
        assertThat(runNow().results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("PAID"));
    }

    // ───────── 수동 실행 ─────────

    @Test
    void dryRun은_대상만_세고_보내지_않는다() {
        Bill bill = confirmed(30_000);

        JobRunReport report = job.run(new JobRunCommand(clock.instant(), null, true));

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.result()).isEqualTo("DRY_RUN");
            assertThat(item.amount()).isEqualTo(30_000);
        });
        verify(transfer, never()).findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                any(LocalDate.class));
        assertThat(billRow(bill.id()).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void userId를_주면_그_사용자의_청구서만_출금한다() {
        Bill mine = confirmed(30_000);
        long other = ssafyUser();
        long otherAccount = account(other);
        long otherCard = owners.card(other, owners.product(), otherAccount, "ACTIVE");
        long others = fixtures.confirmed(other, otherCard, MONTH, withdrawalDate, otherAccount, 9_000);
        long payment = fixtures.payment(other, otherCard, merchant, 9_000, 1, LocalDateTime.now(ZoneOffset.UTC));
        fixtures.item(payment, other, otherCard, others, 1, MONTH, 9_000, "BILLED");

        job.run(new JobRunCommand(clock.instant(), user, false));

        assertThat(billRow(mine.id()).get("status")).isEqualTo("PAID");
        assertThat(billRow(others).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 운영_API로_bill_withdraw를_돌리면_결과에_PAID와_거래_번호가_나온다() throws Exception {
        Bill bill = confirmed(30_000);

        mockMvc.perform(post("/api/admin/jobs/bill-withdraw/run").header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value("bill-withdraw"))
                .andExpect(jsonPath("$.data.summary.targets").value(1))
                .andExpect(jsonPath("$.data.summary.succeeded").value(1))
                .andExpect(jsonPath("$.data.results[0].refType").value("CARD_BILL"))
                .andExpect(jsonPath("$.data.results[0].refId").value(bill.id()))
                .andExpect(jsonPath("$.data.results[0].result").value("PAID"))
                .andExpect(jsonPath("$.data.results[0].amount").value(30_000))
                .andExpect(jsonPath("$.data.results[0].finTransactionNo").value("B1"));
    }

    // ───────── 도우미 ─────────

    record Bill(long id, List<Long> items) {
    }

    /** 이번 달 확정 청구서. 회차 하나에 금액 하나(BILLED), 확정액 = 합계 */
    private Bill confirmed(long... amounts) {
        long total = 0;
        for (long amount : amounts) {
            total += amount;
        }
        long bill = fixtures.confirmed(user, card, MONTH, withdrawalDate, account, total);
        List<Long> items = new java.util.ArrayList<>();
        for (long amount : amounts) {
            items.add(item(bill, amount, "BILLED"));
        }
        return new Bill(bill, items);
    }

    private Bill confirmed(YearMonth month, LocalDate withdrawal, long amount) {
        long bill = fixtures.confirmed(user, card, month, withdrawal, account, amount);
        return new Bill(bill, List.of(item(bill, amount, "BILLED")));
    }

    private long item(long bill, long amount, String status) {
        long payment = fixtures.payment(user, card, merchant, amount, 1, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        return fixtures.item(payment, user, card, bill, 1, MONTH, amount, status);
    }

    private BillWithdrawer.Result withdraw(long bill) {
        return withdrawer.withdraw(bill, today(), false);
    }

    private JobRunReport runNow() {
        return run(clock.instant());
    }

    private JobRunReport run(Instant asOf) {
        return job.run(new JobRunCommand(asOf, null, false));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }

    /** 오늘(포함) 이전의 가장 가까운 영업일 */
    private LocalDate latestBusinessDayBefore(LocalDate date) {
        LocalDate day = date;
        while (!calendar.isBusinessDay(day)) {
            day = day.minusDays(1);
        }
        return day;
    }

    private Map<String, Object> billRow(long id) {
        return jdbc.queryForMap("SELECT * FROM card_bill WHERE card_bill_id = ?", id);
    }

    private Map<String, Object> refundRow(long id) {
        return jdbc.queryForMap("SELECT * FROM refund WHERE refund_id = ?", id);
    }

    private String itemStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM bill_item WHERE bill_item_id = ?", String.class, id);
    }

    /** 가짜 은행 거래내역에 출금 한 줄을 적는다. */
    private void land(TransferTag tag, long amount) {
        bank.add(new BankTransaction("B" + (bank.size() + 1), today(), LocalTime.NOON, BankDirection.WITHDRAWAL,
                amount, BALANCE - amount, tag.value()));
    }

    private List<BankTransaction> matching(TransferTag tag) {
        return bank.stream().filter(t -> t.direction() == tag.direction() && tag.value().equals(t.summary())).toList();
    }

    private TransferResult success() {
        return new TransferResult(Status.SUCCESS, bank.get(bank.size() - 1).bankTransactionNo(), today(), null, null);
    }

    private static TransferResult unknown() {
        return new TransferResult(Status.UNKNOWN, null, null, null, null);
    }

    private static TransferResult notSent() {
        return new TransferResult(Status.NOT_SENT, null, null, null, null);
    }

    private static TransferResult rejected(RejectReason reason, String code) {
        return new TransferResult(Status.REJECTED, null, null, reason, code);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 채번기와 같은 모양: 지금(테스트 시계, 한국 시각) 14자리 + 일련번호 6자리. */
    private String nextTransactionNo() {
        return clock.instant().atZone(KST).format(REQUEST_TIME) + String.format("%06d", sequence.incrementAndGet());
    }

    private long ssafyUser() {
        long id = owners.user();
        jdbc.update("UPDATE app_user SET ssafy_user_key = ?, ssafy_member_id = ? WHERE user_id = ?",
                cipher.encrypt("user-key-" + id, CipherField.APP_USER_SSAFY_USER_KEY), "yamu-" + id + "@yamu.com", id);
        return id;
    }

    private long account(long owner) {
        long id = owners.account(owner);
        jdbc.update("UPDATE linked_account SET account_no_enc = ? WHERE linked_account_id = ?",
                cipher.encrypt(ACCOUNT_NO, CipherField.LINKED_ACCOUNT_NO), id);
        return id;
    }
}
