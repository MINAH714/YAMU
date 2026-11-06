package com.yamu.be.payment.billing.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferResult.RejectReason;
import com.yamu.be.payment.bank.TransferResult.Status;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.billing.BillFixtures;
import com.yamu.be.payment.billing.refund.RefundDepositor.Outcome;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 신용카드 취소 환급 입금 정기 작업({@code refund-payout}, Task 5.4.4)을 실제 MySQL·Redis로 확인한다. 금융망(SsafyTransferApi)만 가짜 은행으로 바꾼다.
 *
 * <p>결제일 출금(S15P31E103-207) 전이라 기한이 정해진 환급은 실제 흐름으로 만들 수 없어, 출금 완료 청구서·환급 대기 회차·환급을
 * SQL로 넣는다(출금 뒤 취소한 모양). 작업은 {@link RefundPayoutJob#run(JobRunCommand)}을 직접 부르거나 운영 API로 부른다.
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@TestPropertySource(properties = "spring.application.name=yamu-refund-deposit-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundDepositIntegrationTest extends MySqlTestSupport {

    static final String ADMIN_KEY = "test-admin-key";
    static final String ACCOUNT_NO = "0012345678901234";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    static final long BALANCE = 1_000_000;

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

    @Autowired RefundPayoutJob job;
    @Autowired MockMvc mockMvc;
    @Autowired RefundDepositor depositor;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PaymentApprovalProperties properties;
    @Autowired PersonalDataCipher cipher;
    @Autowired MutableClock clock;
    @Autowired ApplicationEvents events;

    /** 가짜 은행의 거래내역(환급 계좌 하나) */
    final List<BankTransaction> bank = new CopyOnWriteArrayList<>();
    final AtomicInteger sequence = new AtomicInteger();
    final AtomicInteger months = new AtomicInteger();
    OwnershipFixtures owners;
    BillFixtures bills;
    long user;
    long account;
    long card;
    long merchant;

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
        sequence.set(0);
        months.set(0);
        owners = new OwnershipFixtures(jdbc);
        bills = new BillFixtures(jdbc);
        user = ssafyUser();
        account = account(user);
        card = owners.card(user, owners.product(), account, "ACTIVE");
        merchant = bills.merchant("환급 테스트 가맹점");

        doAnswer(inv -> nextTransactionNo()).when(transfer).newTransactionNo();
        doAnswer(inv -> matching(inv.getArgument(2))).when(transfer)
                .findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class), any(LocalDate.class));
        succeedDeposits();
    }

    // ───────── 입금 성공 ─────────

    @Test
    void 기한이_정해진_대기_환급을_YAMU_R로_저장된_계좌에_입금하고_완료와_회차_REFUNDED로_바꾼다() {
        Refund refund = refundAfterWithdrawal(15_000);

        JobRunReport report = run();

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.refType()).isEqualTo("REFUND");
            assertThat(item.refId()).isEqualTo(refund.id());
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.SUCCEEDED);
            assertThat(item.result()).isEqualTo("COMPLETED");
            assertThat(item.amount()).isEqualTo(15_000L);
            assertThat(item.finTransactionNo()).isEqualTo("D1");
        });
        verify(transfer).deposit(eq("user-key-" + user), eq(ACCOUNT_NO), eq(15_000L), eq(TransferTag.refund(refund.id())),
                anyString());
        Map<String, Object> row = refundRow(refund.id());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("fin_request_no")).isNotNull();
        assertThat(row.get("fin_transaction_no")).isEqualTo("D1");
        assertThat(itemStatus(refund.itemId())).isEqualTo("REFUNDED");
        assertThat(events.stream(RefundCompletedEvent.class))
                .containsExactly(new RefundCompletedEvent(refund.id(), user, refund.paymentId(), 15_000));
        // 입금 전 태그는 환급 생성일(한국) 전날부터 오늘까지 찾는다
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(transfer).findAllByTag(anyString(), anyString(), eq(TransferTag.refund(refund.id())), from.capture(),
                any(LocalDate.class));
        assertThat(from.getValue()).isEqualTo(LocalDate.now(KST).minusDays(1));
    }

    @Test
    void 기한을_넘긴_환급도_계속_입금한다() {
        Refund refund = refundAfterWithdrawal(15_000);
        jdbc.update("UPDATE refund SET due_date = ? WHERE refund_id = ?", LocalDate.now(KST).minusDays(1), refund.id());

        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.COMPLETED);
        assertThat(refundStatus(refund.id())).isEqualTo("COMPLETED");
    }

    @Test
    void 앱에서_연결을_끊은_계좌여도_저장된_환급_계좌로_입금한다() {
        Refund refund = refundAfterWithdrawal(15_000);
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', disconnected_at = ? WHERE linked_account_id = ?",
                LocalDateTime.now(ZoneOffset.UTC), account);

        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.COMPLETED);
        verify(transfer).deposit(anyString(), eq(ACCOUNT_NO), eq(15_000L), any(), anyString());
    }

    // ───────── 입금하지 않는 환급 ─────────

    @Test
    void 기한_없는_출금_대기_환급과_완료_실패_무효_환급은_입금하지_않는다() {
        Refund awaiting = refundAfterWithdrawal(15_000);
        jdbc.update("UPDATE refund SET due_date = NULL WHERE refund_id = ?", awaiting.id());
        for (String status : List.of("COMPLETED", "FAILED", "CANCELED")) {
            Refund other = refundAfterWithdrawal(1_000);
            jdbc.update("UPDATE refund SET status = ?, completed_at = IF(? = 'COMPLETED', ?, NULL) WHERE refund_id = ?",
                    status, status, LocalDateTime.now(ZoneOffset.UTC), other.id());
        }

        assertThat(run().results()).isEmpty();
        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(depositor.deposit(awaiting.id())).isEqualTo(Outcome.SKIPPED);
    }

    // ───────── 이중 입금 방지 ─────────

    @Test
    void 이미_들어간_YAMU_R_입금이_있으면_다시_보내지_않고_완료로_바꾼다() {
        Refund refund = refundAfterWithdrawal(15_000);
        land(TransferTag.refund(refund.id()), 15_000);

        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.COMPLETED);

        verify(transfer, never()).newTransactionNo();
        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(refundRow(refund.id()).get("fin_transaction_no")).isEqualTo("B1");
        assertThat(itemStatus(refund.itemId())).isEqualTo("REFUNDED");
    }

    @Test
    void 입금_뒤_결과를_저장하지_못해도_다음_실행이_태그로_완료하고_다시_보내지_않는다() {
        Refund refund = refundAfterWithdrawal(15_000);
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            throw new IllegalStateException("입금 뒤 서버가 멈춘 상황");
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        assertThat(count(run(), "ERROR")).isEqualTo(1);
        assertThat(refundStatus(refund.id())).isEqualTo("PENDING");

        clock.advance(properties.recoveryGrace().plusSeconds(1));
        assertThat(count(run(), "COMPLETED")).isEqualTo(1);
        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(refundStatus(refund.id())).isEqualTo("COMPLETED");
    }

    @Test
    void 결과_불명이면_대기로_두고_유예_안에는_다시_보내지_않으며_유예_뒤_태그가_있으면_완료한다() {
        Refund refund = refundAfterWithdrawal(15_000);
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            return unknown();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.UNKNOWN);
        assertThat(refundStatus(refund.id())).isEqualTo("PENDING");
        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.IN_FLIGHT);

        clock.advance(properties.recoveryGrace().plusSeconds(1));
        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.COMPLETED);

        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(itemStatus(refund.itemId())).isEqualTo("REFUNDED");
    }

    @Test
    void 결과_불명_뒤_유예가_지나도_태그가_없으면_새_요청_번호로_다시_보낸다() {
        Refund refund = refundAfterWithdrawal(15_000);
        doAnswer(inv -> unknown()).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.UNKNOWN);
        String firstRequestNo = (String) refundRow(refund.id()).get("fin_request_no");

        succeedDeposits();
        clock.advance(properties.recoveryGrace().plusSeconds(1));
        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.COMPLETED);

        ArgumentCaptor<String> requestNos = ArgumentCaptor.forClass(String.class);
        verify(transfer, times(2)).deposit(anyString(), anyString(), eq(15_000L), any(), requestNos.capture());
        assertThat(requestNos.getAllValues().get(0)).isEqualTo(firstRequestNo);
        assertThat(requestNos.getAllValues().get(1)).isNotEqualTo(firstRequestNo);
        assertThat(refundRow(refund.id()).get("fin_request_no")).isEqualTo(requestNos.getAllValues().get(1));
    }

    @Test
    void 요청이_금융망에_가지_않으면_남긴_번호를_지우고_다음_실행이_유예_없이_다시_보낸다() {
        Refund refund = refundAfterWithdrawal(15_000);
        doAnswer(inv -> notSent()).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.NOT_SENT);
        assertThat(refundRow(refund.id()).get("fin_request_no")).isNull();

        succeedDeposits();
        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void 같은_환급을_동시에_두_번_처리해도_한_번만_보낸다() throws Exception {
        Refund refund = refundAfterWithdrawal(15_000);
        doAnswer(inv -> {
            Thread.sleep(300);
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        List<Outcome> outcomes = Concurrently.run(2, () -> depositor.deposit(refund.id()));

        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(outcomes).contains(Outcome.COMPLETED);
        assertThat(outcomes).filteredOn(o -> o != Outcome.COMPLETED)
                .allMatch(o -> o == Outcome.CONTENDED || o == Outcome.IN_FLIGHT || o == Outcome.SKIPPED);
        assertThat(refundStatus(refund.id())).isEqualTo("COMPLETED");
    }

    // ───────── 실패·운영 확인 ─────────

    @Test
    void 은행이_거절하면_FAILED로_끝내고_이벤트를_남기며_다시_보내지_않는다() {
        Refund refund = refundAfterWithdrawal(15_000);
        doAnswer(inv -> rejected(RejectReason.ACCOUNT_NOT_FOUND)).when(transfer)
                .deposit(anyString(), anyString(), anyLong(), any(), anyString());

        JobRunReport report = run();
        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.FAILED);
            assertThat(item.result()).isEqualTo("FAILED");
        });
        assertThat(refundStatus(refund.id())).isEqualTo("FAILED");
        assertThat(itemStatus(refund.itemId())).isEqualTo("REFUND_PENDING");
        assertThat(events.stream(RefundFailedEvent.class)).containsExactly(
                new RefundFailedEvent(refund.id(), user, refund.paymentId(), 15_000, RejectReason.ACCOUNT_NOT_FOUND));

        clock.advance(properties.recoveryGrace().plusSeconds(1));
        assertThat(run().results()).isEmpty();
        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void YAMU_R_입금이_두_건이거나_금액이_다르면_대기로_두고_보내지_않는다() {
        Refund duplicated = refundAfterWithdrawal(15_000);
        land(TransferTag.refund(duplicated.id()), 15_000);
        land(TransferTag.refund(duplicated.id()), 15_000);
        Refund mismatched = refundAfterWithdrawal(15_000);
        land(TransferTag.refund(mismatched.id()), 14_000);

        assertThat(depositor.deposit(duplicated.id())).isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(depositor.deposit(mismatched.id())).isEqualTo(Outcome.NEEDS_REVIEW);

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(refundStatus(duplicated.id())).isEqualTo("PENDING");
        assertThat(refundStatus(mismatched.id())).isEqualTo("PENDING");
    }

    @Test
    void SSAFY_회원_키를_읽을_수_없으면_대기로_두고_보내지_않는다() {
        Refund refund = refundAfterWithdrawal(15_000);
        jdbc.update("UPDATE app_user SET ssafy_user_key = NULL WHERE user_id = ?", user);

        assertThat(depositor.deposit(refund.id())).isEqualTo(Outcome.NEEDS_REVIEW);
        verify(transfer, never()).findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                any(LocalDate.class));
        assertThat(refundStatus(refund.id())).isEqualTo("PENDING");
    }

    @Test
    void 금융망_조회가_실패한_건은_오류로_세고_나머지_환급은_계속_입금한다() {
        Refund broken = refundAfterWithdrawal(15_000);
        Refund fine = refundAfterWithdrawal(5_000);
        doAnswer(inv -> {
            TransferTag tag = inv.getArgument(2);
            if (tag.equals(TransferTag.refund(broken.id()))) {
                throw new IllegalStateException("거래내역 조회 실패");
            }
            return matching(tag);
        }).when(transfer).findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                any(LocalDate.class));

        JobRunReport report = run();

        assertThat(count(report, "ERROR")).isEqualTo(1);
        assertThat(count(report, "COMPLETED")).isEqualTo(1);
        assertThat(report.count(JobRunReport.Outcome.FAILED)).isEqualTo(1);
        assertThat(refundStatus(broken.id())).isEqualTo("PENDING");
        assertThat(refundRow(broken.id()).get("fin_request_no")).isNull();
        assertThat(refundStatus(fine.id())).isEqualTo("COMPLETED");
    }

    // ───────── 정기 작업·운영 API ─────────

    @Test
    void 자동으로_판단하지_않은_환급은_결과에_NEEDS_REVIEW_실패로_남긴다() {
        Refund refund = refundAfterWithdrawal(15_000);
        land(TransferTag.refund(refund.id()), 14_000);

        assertThat(run().results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.FAILED);
            assertThat(item.result()).isEqualTo("NEEDS_REVIEW");
        });
        assertThat(refundStatus(refund.id())).isEqualTo("PENDING");
    }

    @Test
    void dryRun은_대상만_세고_입금하지_않는다() {
        Refund refund = refundAfterWithdrawal(15_000);

        JobRunReport report = job.run(new JobRunCommand(clock.instant(), null, true));

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.result()).isEqualTo("DRY_RUN");
            assertThat(item.amount()).isEqualTo(15_000L);
        });
        verify(transfer, never()).findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class),
                any(LocalDate.class));
        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(refundStatus(refund.id())).isEqualTo("PENDING");
    }

    @Test
    void userId를_주면_그_사용자의_환급만_입금한다() {
        Refund mine = refundAfterWithdrawal(15_000);
        long other = ssafyUser();
        long otherAccount = account(other);
        long otherCard = owners.card(other, owners.product(), otherAccount, "ACTIVE");
        Refund others = refundFor(other, otherCard, otherAccount, 5_000);

        JobRunReport report = job.run(new JobRunCommand(clock.instant(), user, false));

        assertThat(report.results()).extracting(JobRunReport.Item::refId).containsExactly(mine.id());
        assertThat(refundStatus(mine.id())).isEqualTo("COMPLETED");
        assertThat(refundStatus(others.id())).isEqualTo("PENDING");
    }

    @Test
    void 정기_작업_설정이_꺼져_있으면_스케줄_실행은_아무것도_하지_않는다() {
        Refund refund = refundAfterWithdrawal(15_000);

        job.scheduled();

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(refundStatus(refund.id())).isEqualTo("PENDING");
    }

    @Test
    void 운영_API로_refund_payout을_돌리면_입금하고_결과를_돌려준다() throws Exception {
        Refund refund = refundAfterWithdrawal(15_000);

        mockMvc.perform(post("/api/admin/jobs/refund-payout/run").header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + user + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value("refund-payout"))
                .andExpect(jsonPath("$.data.summary.targets").value(1))
                .andExpect(jsonPath("$.data.summary.succeeded").value(1))
                .andExpect(jsonPath("$.data.results[0].refType").value("REFUND"))
                .andExpect(jsonPath("$.data.results[0].refId").value(refund.id()))
                .andExpect(jsonPath("$.data.results[0].result").value("COMPLETED"))
                .andExpect(jsonPath("$.data.results[0].amount").value(15_000))
                .andExpect(jsonPath("$.data.results[0].finTransactionNo").value("D1"));

        assertThat(refundStatus(refund.id())).isEqualTo("COMPLETED");
    }

    // ───────── 도우미 ─────────

    private JobRunReport run() {
        return job.run(JobRunCommand.now(clock));
    }

    private static long count(JobRunReport report, String result) {
        return report.results().stream().filter(item -> item.result().equals(result)).count();
    }

    record Refund(long id, long itemId, long paymentId) {
    }

    /**
     * 출금 뒤 취소한 모양: 출금 완료 청구서(확정액 = 회차 금액), 환급 대기 회차, 기한이 정해진 대기 환급(취소일 + 3영업일 자리에 7일 뒤).
     */
    private Refund refundAfterWithdrawal(long amount) {
        return refundFor(user, card, account, amount);
    }

    private Refund refundFor(long user, long card, long account, long amount) {
        LocalDate withdrawn = LocalDate.now(KST).minusDays(1);
        // 청구서는 카드·청구 월마다 하나라 환급마다 다른 달을 쓴다
        YearMonth month = NOVEMBER.minusMonths(months.getAndIncrement());
        long payment = bills.payment(user, card, merchant, amount, 1, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        long bill = bills.paid(user, card, month, withdrawn, account, amount, "W" + month);
        long item = bills.item(payment, user, card, bill, 1, month, amount, "REFUND_PENDING");
        long refund = bills.refund(payment, item, user, account, amount, "PENDING");
        jdbc.update("UPDATE refund SET due_date = ? WHERE refund_id = ?", LocalDate.now(KST).plusDays(7), refund);
        return new Refund(refund, item, payment);
    }

    private Map<String, Object> refundRow(long refundId) {
        return jdbc.queryForMap("""
                SELECT status, completed_at, fin_request_no, fin_transaction_no FROM refund WHERE refund_id = ?
                """, refundId);
    }

    private String refundStatus(long refundId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE refund_id = ?", String.class, refundId);
    }

    private String itemStatus(long billItemId) {
        return jdbc.queryForObject("SELECT status FROM bill_item WHERE bill_item_id = ?", String.class, billItemId);
    }

    private void succeedDeposits() {
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    /** 가짜 은행 거래내역에 한 줄을 적는다. */
    private void land(TransferTag tag, long amount) {
        bank.add(new BankTransaction("B" + (bank.size() + 1), LocalDate.now(KST), LocalTime.NOON, tag.direction(),
                amount, tag.direction() == BankDirection.DEPOSIT ? BALANCE + amount : BALANCE - amount, tag.value()));
    }

    private List<BankTransaction> matching(TransferTag tag) {
        return bank.stream().filter(t -> t.direction() == tag.direction() && tag.value().equals(t.summary())).toList();
    }

    private static TransferResult success() {
        return new TransferResult(Status.SUCCESS, "D1", LocalDate.now(KST), null, null);
    }

    private static TransferResult unknown() {
        return new TransferResult(Status.UNKNOWN, null, null, null, null);
    }

    private static TransferResult notSent() {
        return new TransferResult(Status.NOT_SENT, null, null, null, null);
    }

    private static TransferResult rejected(RejectReason reason) {
        return new TransferResult(Status.REJECTED, null, null, reason, "A1003");
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
