package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferTag;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 체크카드 승인 출금 뒤 잔액 읽기(S15P31E103-569). 승인은 이미 저장된 뒤라 어떤 실패도 null로 끝나야 한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckCardBalanceReaderTest {

    static final long PAYMENT_ID = 1024L;
    static final long USER_ID = 7L;
    static final long CARD_ID = 16L;
    static final String TX_NO = "20261022143005000001";
    static final BankAccess ACCESS = new BankAccess("user-key", "0012345678901234", CardType.CHECK, 9L);

    @Mock PaymentAccountReader accounts;
    @Mock SsafyTransferApi transfer;

    CheckCardBalanceReader reader;

    @BeforeEach
    void setUp() {
        reader = new CheckCardBalanceReader(accounts, transfer);
        when(accounts.forCard(USER_ID, CARD_ID)).thenReturn(Optional.of(ACCESS));
    }

    @Test
    void 이_결제_태그와_요청_번호로_찾은_출금_한_건의_거래_후_잔액을_돌려준다() {
        when(transfer.findAllByTag("user-key", "0012345678901234", TransferTag.payment(PAYMENT_ID), TX_NO))
                .thenReturn(List.of(withdrawal(14_000, 986_000)));

        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(14_000, TX_NO))).isEqualTo(986_000L);
    }

    @Test
    void 내역에_아직_없으면_null이다() {
        when(transfer.findAllByTag(anyString(), anyString(), any(), anyString())).thenReturn(List.of());

        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(14_000, TX_NO))).isNull();
    }

    @Test
    void 같은_태그가_두_건이면_어느_쪽인지_모르므로_null이다() {
        when(transfer.findAllByTag(anyString(), anyString(), any(), anyString()))
                .thenReturn(List.of(withdrawal(14_000, 986_000), withdrawal(14_000, 972_000)));

        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(14_000, TX_NO))).isNull();
    }

    @Test
    void 금액이_출금액과_다르면_null이다() {
        when(transfer.findAllByTag(anyString(), anyString(), any(), anyString()))
                .thenReturn(List.of(withdrawal(15_000, 985_000)));

        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(14_000, TX_NO))).isNull();
    }

    @Test
    void 거래내역_조회가_실패하면_예외_없이_null이다() {
        when(transfer.findAllByTag(anyString(), anyString(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE));

        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(14_000, TX_NO))).isNull();
    }

    @Test
    void 계좌를_다시_찾지_못하면_조회하지_않고_null이다() {
        when(accounts.forCard(USER_ID, CARD_ID)).thenReturn(Optional.empty());

        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(14_000, TX_NO))).isNull();
        verify(transfer, never()).findAllByTag(anyString(), anyString(), any(), anyString());
    }

    @Test
    void 출금이_없었던_승인_신용카드나_0원은_읽지_않는다() {
        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(15_000, null))).isNull();
        assertThat(reader.balanceAfter(PAYMENT_ID, USER_ID, CARD_ID, approved(0, null))).isNull();

        verify(accounts, never()).forCard(any(), any());
        verify(transfer, never()).findAllByTag(anyString(), anyString(), any(), anyString());
    }

    private static ApprovalOutcome.Approved approved(long charged, String finRequestNo) {
        return new ApprovalOutcome.Approved(charged, charged, 0, LocalDate.of(2026, 10, 1), List.of(), finRequestNo,
                finRequestNo == null ? null : "1101", null);
    }

    private static BankTransaction withdrawal(long amount, long balanceAfter) {
        return new BankTransaction("1101", LocalDate.of(2026, 10, 22), LocalTime.of(14, 30), BankDirection.WITHDRAWAL,
                amount, balanceAfter, TransferTag.payment(PAYMENT_ID).value());
    }
}
