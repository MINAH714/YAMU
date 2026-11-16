package com.yamu.be.payment.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.approve.WithdrawalRecovery;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferResult.RejectReason;
import com.yamu.be.payment.bank.TransferResult.Status;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 체크카드 전체 취소(Task 5.3.3)를 실제 MySQL·Redis로 확인한다. 금융망(SsafyTransferApi)만 가짜 은행으로 바꾼다.
 * 가짜 은행은 입금 성공을 거래내역에 적고, 태그 조회는 그 거래내역에서 태그가 같은 줄을 돌려준다.
 * 승인된 결제와 원 출금(YAMU-P)은 직접 넣는다(출금 승인 흐름은 {@code CheckCardApprovalIntegrationTest}가 본다).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-check-card-cancel-test")
@Import(CheckCardCancelIntegrationTest.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckCardCancelIntegrationTest extends MySqlTestSupport {

    static final String ACCOUNT_NO = "0012345678901234";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
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
    @MockitoSpyBean CheckRefundAttemptStore refundAttempts;
    @MockitoSpyBean CancellationLedger ledger;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PaymentTransactionRepository payments;
    @Autowired PaymentApprovalProperties properties;
    @Autowired PersonalDataCipher cipher;
    @Autowired MutableClock clock;

    /** 가짜 은행의 거래내역(연결 계좌 하나) */
    final List<BankTransaction> bank = new CopyOnWriteArrayList<>();
    final AtomicInteger sequence = new AtomicInteger();
    OwnershipFixtures fixtures;
    long user;
    long account;
    long checkCard;
    long checkProduct;
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
        // 승인·취소·청구 사건이 쌓은 알림(Task 9.3.2)이 사용자를 참조한다
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        bank.clear();
        sequence.set(0);
        fixtures = new OwnershipFixtures(jdbc);
        user = ssafyUser();
        account = account(user);
        checkProduct = fixtures.product();
        jdbc.update("UPDATE card_product SET card_type = 'CHECK', billing_cutoff_days = NULL WHERE card_product_id = ?",
                checkProduct);
        checkCard = fixtures.card(user, checkProduct, account, "ACTIVE");
        merchant = merchant();

        doAnswer(inv -> nextTransactionNo()).when(transfer).newTransactionNo();
        doAnswer(inv -> matching(inv.getArgument(2)))
                .when(transfer).findAllByTag(anyString(), anyString(), any(TransferTag.class), anyString());
        doAnswer(inv -> matching(inv.getArgument(2))).when(transfer)
                .findAllByTag(anyString(), anyString(), any(TransferTag.class), any(LocalDate.class), any(LocalDate.class));
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    // ───────── 취소 성공 ─────────

    @Test
    void 원_출금액을_YAMU_C_태그로_입금하고_CANCELED와_입금_뒤_잔액을_돌려준다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString())
                .andExpect(jsonPath("$.data.billItems.length()").value(0))
                .andExpect(jsonPath("$.data.refunds.length()").value(0))
                .andExpect(jsonPath("$.data.accountBalanceAfter").value(BALANCE + 15_000));

        verify(transfer).deposit(eq("user-key-" + user), eq(ACCOUNT_NO), eq(15_000L),
                eq(TransferTag.checkRefund(paymentId)), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.CANCELED);
        assertThat(refundAccount(paymentId)).isEqualTo(account);
        // 환불 태그는 승인일(한국) 전날부터 오늘까지 찾는다
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(transfer).findAllByTag(anyString(), anyString(), eq(TransferTag.checkRefund(paymentId)), from.capture(),
                any(LocalDate.class));
        assertThat(from.getValue()).isEqualTo(LocalDate.now(KST).minusDays(1));
    }

    @Test
    void 즉시할인으로_승인액보다_적게_출금했으면_실제_출금액만_환불한다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 13_500);

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(transfer).deposit(anyString(), anyString(), eq(13_500L), any(), anyString());
    }

    @Test
    void 출금_없이_승인된_0원_결제는_입금_없이_취소된다() throws Exception {
        long paymentId = approved(15_000, null);

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.accountBalanceAfter").doesNotExist());

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        verify(transfer, never()).newTransactionNo();
        assertThat(refundAccount(paymentId)).isNull();
    }

    @Test
    void 입금_뒤_저장_전에_카드_계좌를_바꿔도_환불을_넣은_계좌를_적는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        long newAccount = account(user);
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            // 입금이 끝난 직후, 취소를 저장하기 전에 사용자가 결제 계좌를 바꿨다
            jdbc.update("UPDATE user_card SET linked_account_id = ? WHERE user_card_id = ?", newAccount, checkCard);
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(refundAccount(paymentId)).isEqualTo(account);
    }

    @Test
    void 입금_뒤_잔액을_거래내역에서_못_찾아도_취소는_성공하고_잔액은_null() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        // 입금은 성공했지만 SSAFY 거래내역에는 아직 안 보인다
        doAnswer(inv -> success()).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.accountBalanceAfter").doesNotExist());
    }

    // ───────── 이중 환불 방지 ─────────

    @Test
    void 이미_환불_입금이_있으면_다시_입금하지_않고_CANCELED로_맞춘다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        land(TransferTag.checkRefund(paymentId), 15_000);

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.accountBalanceAfter").value(BALANCE + 15_000));

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        // 거래내역에서 찾은 입금도 그 계좌를 적는다
        assertThat(refundAccount(paymentId)).isEqualTo(account);
    }

    @Test
    void 입금_결과_불명이면_502이고_유예_안의_재시도는_409_처리_중이며_다시_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doAnswer(inv -> unknown()).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_UNAVAILABLE"));
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);

        // SSAFY가 아직 처리 중이라 태그가 안 보이는 사이의 재시도
        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));

        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 결과_불명_입금이_뒤늦게_처리됐으면_유예_뒤_재시도는_입금_없이_CANCELED() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            return unknown();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().isBadGateway());

        clock.advance(properties.recoveryGrace().plusSeconds(1));
        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(matching(TransferTag.checkRefund(paymentId))).hasSize(1);
    }

    @Test
    void 결과_불명_입금이_유예_뒤에도_없으면_다시_입금한다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doAnswer(inv -> unknown()).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().isBadGateway());

        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        clock.advance(properties.recoveryGrace().plusSeconds(1));
        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(transfer, times(2)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(matching(TransferTag.checkRefund(paymentId))).hasSize(1);
    }

    @Test
    void 입금_뒤_취소_저장이_실패해도_재시도는_태그를_찾아_입금_없이_CANCELED() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doThrow(new IllegalStateException("원장 저장 실패")).when(ledger).reverse(any(), any());

        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().is5xxServerError());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);

        doCallRealMethod().when(ledger).reverse(any(), any());
        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void 보낸_시각을_남기지_못하면_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doThrow(new RedisConnectionFailureException("Redis 끊김")).when(refundAttempts).recordSending(any(), any());

        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().is5xxServerError());

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 두_기기가_동시에_취소해도_입금은_한_번이다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doAnswer(inv -> {
            Thread.sleep(300);
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());

        List<String> results = Concurrently.run(3, () -> {
            String body = cancel(paymentId, UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString();
            if (body.contains("\"success\":true")) {
                return "OK";
            }
            return body.contains("ALREADY_CANCELED") || body.contains("IDEMPOTENCY_IN_PROGRESS") ? "REJECTED" : body;
        });

        assertThat(results).containsExactlyInAnyOrder("OK", "REJECTED", "REJECTED");
        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void 같은_키로_다시_보내면_처음_응답을_돌려주고_다시_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        String key = UUID.randomUUID().toString();

        String first = cancel(paymentId, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = cancel(paymentId, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void 정산_잠금을_다른_처리가_잡고_있으면_409_처리_중이고_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        redis.opsForValue().set(WithdrawalRecovery.settleLockKey(paymentId), "other", Duration.ofMinutes(1));

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    // ───────── 입금 실패 ─────────

    @Test
    void 입금이_거절되면_502이고_APPROVED로_남아_바로_다시_취소할_수_있다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doAnswer(inv -> rejected(RejectReason.OTHER)).when(transfer)
                .deposit(anyString(), anyString(), anyLong(), any(), anyString());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("FIN_API_ERROR"));
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);

        // 돈이 안 움직인 것이 확실하므로 유예를 기다리지 않는다
        doAnswer(inv -> {
            land(inv.getArgument(3), inv.getArgument(2));
            return success();
        }).when(transfer).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void 입금_계좌가_해지돼_거절되면_409_ACCOUNT_UNAVAILABLE() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doAnswer(inv -> rejected(RejectReason.ACCOUNT_NOT_FOUND)).when(transfer)
                .deposit(anyString(), anyString(), anyLong(), any(), anyString());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("ACCOUNT_UNAVAILABLE"));
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 금융망_채번에_실패하면_502이고_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE))
                .when(transfer).newTransactionNo();

        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().isBadGateway());

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 원_출금을_조회하지_못하면_502이고_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        doThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE))
                .when(transfer).findAllByTag(anyString(), anyString(), eq(TransferTag.payment(paymentId)), anyString());

        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().isBadGateway());

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    // ───────── 자동 판단하지 않는 경우·잘못된 요청 ─────────

    @Test
    void 카드를_해제해_입금_계좌가_없으면_409_ACCOUNT_UNAVAILABLE이고_금융망을_부르지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        jdbc.update("UPDATE user_card SET status = 'UNREGISTERED' WHERE user_card_id = ?", checkCard);

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("ACCOUNT_UNAVAILABLE"));

        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 원_출금을_거래내역에서_못_찾으면_409_REFUND_NEEDS_REVIEW() throws Exception {
        long paymentId = approved(15_000, nextTransactionNo());

        expectNeedsReview(paymentId);
    }

    @Test
    void 원_출금액이_승인액보다_크면_409_REFUND_NEEDS_REVIEW() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 16_000);

        expectNeedsReview(paymentId);
    }

    @Test
    void 환불_태그가_두_건이면_409_REFUND_NEEDS_REVIEW() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        land(TransferTag.checkRefund(paymentId), 15_000);
        land(TransferTag.checkRefund(paymentId), 15_000);

        expectNeedsReview(paymentId);
    }

    @Test
    void 환불_태그_금액이_출금액과_다르면_409_REFUND_NEEDS_REVIEW() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        land(TransferTag.checkRefund(paymentId), 10_000);

        expectNeedsReview(paymentId);
    }

    @Test
    void 다른_사용자의_체크카드_결제는_404이고_금융망을_부르지_않는다() throws Exception {
        long other = ssafyUser();
        long otherAccount = account(other);
        long otherCard = fixtures.card(other, checkProduct, otherAccount, "ACTIVE");
        long paymentId = insertApproved(other, otherCard, 15_000, nextTransactionNo());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        verify(transfer, never()).findAllByTag(anyString(), anyString(), any(TransferTag.class), anyString());
        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void 이미_취소한_체크카드_결제는_409_ALREADY_CANCELED이고_다시_입금하지_않는다() throws Exception {
        long paymentId = approvedWithWithdrawal(15_000, 15_000);
        cancel(paymentId, UUID.randomUUID().toString()).andExpect(status().isOk());

        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CANCELED"));

        verify(transfer, times(1)).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }

    // ───────── 도우미 ─────────

    private void expectNeedsReview(long paymentId) throws Exception {
        cancel(paymentId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("REFUND_NEEDS_REVIEW"));
        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(paymentStatus(paymentId)).isEqualTo(PaymentStatus.APPROVED);
    }

    private ResultActions cancel(long paymentId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/payments/{paymentId}/cancel", paymentId)
                .with(AuthUsers.asUser(user))
                .header("Idempotency-Key", idempotencyKey));
    }

    private PaymentStatus paymentStatus(long paymentId) {
        return payments.findById(paymentId).orElseThrow().getStatus();
    }

    /** 결제 행에 적힌 체크카드 환불 계좌(S15P31E103-323). */
    private Long refundAccount(long paymentId) {
        return jdbc.queryForObject(
                "SELECT refund_linked_account_id FROM payment_transaction WHERE payment_transaction_id = ?", Long.class,
                paymentId);
    }

    /** 승인된 체크카드 결제와 그 원 출금(YAMU-P) 한 줄. */
    private long approvedWithWithdrawal(long approvedAmount, long withdrawnAmount) {
        long paymentId = approved(approvedAmount, nextTransactionNo());
        land(TransferTag.payment(paymentId), withdrawnAmount);
        return paymentId;
    }

    private long approved(long approvedAmount, String finRequestNo) {
        return insertApproved(user, checkCard, approvedAmount, finRequestNo);
    }

    private long insertApproved(long owner, long userCardId, long approvedAmount, String finRequestNo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", owner);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", "YAMU");
        row.put("status", "APPROVED");
        row.put("requested_amount", approvedAmount);
        row.put("installment_months", 1);
        row.put("requested_at", now);
        row.put("user_card_id", userCardId);
        row.put("card_product_id", checkProduct);
        row.put("approved_amount", approvedAmount);
        row.put("recognized_performance_amount", 0L);
        row.put("performance_month", LocalDate.now(KST).withDayOfMonth(1));
        row.put("approved_at", now);
        if (finRequestNo != null) {
            row.put("fin_request_no", finRequestNo);
            row.put("fin_transaction_no", "W" + sequence.get());
        }
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    /** 가짜 은행 거래내역에 한 줄을 적는다. 입금이면 잔액이 늘어난다. */
    private void land(TransferTag tag, long amount) {
        long after = tag.direction() == BankDirection.DEPOSIT ? BALANCE + amount : BALANCE - amount;
        bank.add(new BankTransaction("B" + (bank.size() + 1), LocalDate.now(KST), LocalTime.NOON, tag.direction(), amount,
                after, tag.value()));
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

    private static TransferResult rejected(RejectReason reason) {
        return new TransferResult(Status.REJECTED, null, null, reason, "A9999");
    }

    /** 채번기와 같은 모양: 지금(테스트 시계, 한국 시각) 14자리 + 일련번호 6자리. */
    private String nextTransactionNo() {
        return clock.instant().atZone(KST).format(REQUEST_TIME) + String.format("%06d", sequence.incrementAndGet());
    }

    private long ssafyUser() {
        long id = fixtures.user();
        jdbc.update("UPDATE app_user SET ssafy_user_key = ?, ssafy_member_id = ? WHERE user_id = ?",
                cipher.encrypt("user-key-" + id, CipherField.APP_USER_SSAFY_USER_KEY), "yamu-" + id + "@yamu.com", id);
        return id;
    }

    private long account(long owner) {
        long id = fixtures.account(owner);
        jdbc.update("UPDATE linked_account SET account_no_enc = ? WHERE linked_account_id = ?",
                cipher.encrypt(ACCOUNT_NO, CipherField.LINKED_ACCOUNT_NO), id);
        return id;
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페", "brand_code", "TEST_CAFE",
                "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now));
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }
}
