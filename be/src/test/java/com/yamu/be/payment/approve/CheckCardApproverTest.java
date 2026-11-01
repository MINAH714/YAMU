package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.payment.bank.TransferResult.RejectReason;
import com.yamu.be.payment.bank.TransferResult.Status;
import com.yamu.be.payment.bank.TransferTag;
import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 체크카드 출금 승인의 분기. 금융망은 가짜다. 실제 순서·DB 상태는 CheckCardApprovalIntegrationTest가 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckCardApproverTest {

    static final long PAYMENT_ID = 1024L;
    static final String TX_NO = "20261022143005000001";
    static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 10, 22, 5, 30);
    static final LocalDateTime NOW = REQUESTED_AT.plusSeconds(5);
    static final CardSummary CHECK = new CardSummary(16L, 4L, CardType.CHECK, false, null, "5678", "TEST", "테스트 체크");
    static final BankAccess ACCESS = new BankAccess("user-key", "0012345678901234", CardType.CHECK, 9L);

    @Mock PaymentAccountReader accounts;
    @Mock SsafyTransferApi transfer;
    @Mock PaymentTransactionRepository payments;
    @Mock ApprovalAttemptStore attempts;
    @Mock TransactionTemplate transaction;

    CheckCardApprover approver;
    PaymentTransaction payment;

    @BeforeEach
    void setUp() {
        approver = new CheckCardApprover(accounts, transfer, payments, attempts, transaction);
        payment = processing();
        when(payments.findById(PAYMENT_ID)).thenAnswer(inv -> Optional.of(payment));
        when(payments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transaction.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<Object>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)));
        when(accounts.forCard(7L, 16L)).thenReturn(Optional.of(ACCESS));
        when(transfer.newTransactionNo()).thenReturn(TX_NO);
    }

    @Test
    void 출금이_성공하면_요청_번호와_거래_번호를_담아_승인한다() {
        when(transfer.withdraw(anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(new TransferResult(Status.SUCCESS, "1101", LocalDate.of(2026, 10, 22), null, null));

        ApprovalOutcome outcome = approver.approve(command(candidate(15_000)));

        ApprovalOutcome.Approved approved = (ApprovalOutcome.Approved) outcome;
        assertThat(approved.approvedAmount()).isEqualTo(15_000);
        assertThat(approved.chargedAmount()).isEqualTo(15_000);
        assertThat(approved.finRequestNo()).isEqualTo(TX_NO);
        assertThat(approved.finTransactionNo()).isEqualTo("1101");
        assertThat(approved.performanceMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(approved.recognizedPerformanceAmount()).isZero();
        verify(transfer).withdraw("user-key", "0012345678901234", 15_000, TransferTag.payment(PAYMENT_ID), TX_NO);
        verify(attempts).attachFinRequestNo(PAYMENT_ID, 1, TX_NO);
    }

    @Test
    void 출금을_부르기_전에_요청_번호를_결제_행에_먼저_저장한다() {
        when(transfer.withdraw(anyString(), anyString(), anyLong(), any(), anyString())).thenAnswer(inv -> {
            // 출금 호출 시점에 행에는 이미 같은 번호가 있어야 한다
            assertThat(payment.getFinRequestNo()).isEqualTo(inv.getArgument(4));
            return new TransferResult(Status.SUCCESS, "1101", LocalDate.of(2026, 10, 22), null, null);
        });

        approver.approve(command(null));

        InOrder order = inOrder(payments, transfer);
        order.verify(payments).saveAndFlush(payment);
        order.verify(transfer).withdraw(anyString(), anyString(), anyLong(), any(), eq(TX_NO));
    }

    @Test
    void 후보의_출금액이_0원이면_채번도_출금도_하지_않고_승인한다() {
        ApprovalOutcome outcome = approver.approve(command(candidate(0)));

        ApprovalOutcome.Approved approved = (ApprovalOutcome.Approved) outcome;
        assertThat(approved.chargedAmount()).isZero();
        assertThat(approved.finRequestNo()).isNull();
        assertThat(approved.finTransactionNo()).isNull();
        verify(transfer, never()).newTransactionNo();
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        assertThat(payment.getFinRequestNo()).isNull();
    }

    @Test
    void 출금할_계좌나_키가_없으면_ACCOUNT_UNAVAILABLE로_거절하고_출금하지_않는다() {
        when(accounts.forCard(7L, 16L)).thenReturn(Optional.empty());

        ApprovalOutcome outcome = approver.approve(command(null));

        assertThat(((ApprovalOutcome.Declined) outcome).code()).isEqualTo(FailureCode.ACCOUNT_UNAVAILABLE);
        verify(transfer, never()).newTransactionNo();
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void 잔액_부족_계좌_없음_그_밖의_거절은_각각의_사유로_DECLINED다() {
        when(transfer.withdraw(anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(new TransferResult(Status.REJECTED, null, null, RejectReason.INSUFFICIENT_BALANCE, "A1014"))
                .thenReturn(new TransferResult(Status.REJECTED, null, null, RejectReason.ACCOUNT_NOT_FOUND, "A1003"))
                .thenReturn(new TransferResult(Status.REJECTED, null, null, RejectReason.OTHER, "A9999"));

        assertThat(((ApprovalOutcome.Declined) approver.approve(command(null))).code())
                .isEqualTo(FailureCode.INSUFFICIENT_BALANCE);
        payment = processing();
        assertThat(((ApprovalOutcome.Declined) approver.approve(command(null))).code())
                .isEqualTo(FailureCode.ACCOUNT_UNAVAILABLE);
        payment = processing();
        assertThat(((ApprovalOutcome.Declined) approver.approve(command(null))).code())
                .isEqualTo(FailureCode.ISSUER_DECLINED);
    }

    @Test
    void 결과를_모르면_FIN_API_UNAVAILABLE_예외이고_요청_번호는_행에_남는다() {
        when(transfer.withdraw(anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(new TransferResult(Status.UNKNOWN, null, null, null, "H1007"));

        assertThatThrownBy(() -> approver.approve(command(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE);
        assertThat(payment.getFinRequestNo()).isEqualTo(TX_NO);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void 금융망에_보내지_못했거나_채번에_실패하면_ApprovalNotSentException이다() {
        when(transfer.withdraw(anyString(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(new TransferResult(Status.NOT_SENT, null, null, null, null));
        assertThatThrownBy(() -> approver.approve(command(null))).isInstanceOf(ApprovalNotSentException.class);

        payment = processing();
        doThrow(FinApiException.timedOut("issue")).when(transfer).newTransactionNo();
        assertThatThrownBy(() -> approver.approve(command(null))).isInstanceOf(ApprovalNotSentException.class);
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), eq("issue"));
        assertThat(payment.getFinRequestNo()).isNull();
    }

    @Test
    void 결제_행이_PROCESSING이_아니거나_동시_수정이면_출금하지_않고_PAYMENT_IN_PROGRESS다() {
        payment = requested();
        assertThatThrownBy(() -> approver.approve(command(null)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);

        payment = processing();
        doThrow(new ObjectOptimisticLockingFailureException("payment", null)).when(transaction).execute(any());
        assertThatThrownBy(() -> approver.approve(command(null)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
    }

    // ───────── 도우미 ─────────

    private static ApprovalCommand command(Candidate candidate) {
        return new ApprovalCommand(PAYMENT_ID, 7L, PaymentTerms.lumpSum(15_000), CHECK, candidate, REQUESTED_AT, NOW, 1);
    }

    private static Candidate candidate(long chargedAmount) {
        return new Candidate(1, CHECK, 0, List.of(), chargedAmount, "mock", null, new RuleVersion(4L, 0));
    }

    private static PaymentTransaction requested() {
        PaymentTransaction payment = PaymentTransaction.request(7L, 1L, PaymentChannel.OFFLINE, FacilityType.GENERAL,
                null, "4b4a1f4e-0d1e-4a1e-9b4e-5f7d2c1a0001", new byte[32], 15_000, 1, REQUESTED_AT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    private static PaymentTransaction processing() {
        PaymentTransaction payment = requested();
        payment.startApproval(16L, 4L);
        return payment;
    }
}
