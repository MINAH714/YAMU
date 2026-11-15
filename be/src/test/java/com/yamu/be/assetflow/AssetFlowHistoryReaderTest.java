package com.yamu.be.assetflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.payment.bank.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AssetFlowHistoryReaderTest {
    SsafyTransferApi bank=mock(SsafyTransferApi.class);
    PersonalDataCipher cipher=mock(PersonalDataCipher.class);
    LinkedAccount account=mock(LinkedAccount.class);
    AssetFlowHistoryReader reader=new AssetFlowHistoryReader(bank,cipher);
    LocalDate from=LocalDate.of(2026,11,1);
    Instant at=Instant.parse("2026-11-13T03:00:00Z");
    BankHistory snapshot(LocalDate start,LocalDate end,Long count) {
        return new BankHistory(start,end,null,count,List.of(),at);
    }
    BankHistory read(LocalDate to) {
        when(cipher.decrypt(any(),any())).thenReturn("test-account");
        return reader.read("test-key",account,from,to,System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
    }
    @Test void splitsTruncatedRangeAndRevalidatesMergedCoverage() {
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenAnswer(i->{
            LocalDate start=i.getArgument(2),end=i.getArgument(3);
            return snapshot(start,end,start.equals(end)?0L:3L);
        });
        assertThat(read(from.plusDays(3)).complete()).isTrue();
        verify(bank,times(7)).historySnapshot(anyString(),anyString(),any(),any(),isNull());
    }
    @Test void stopsAtSingleDayTruncation() {
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenReturn(snapshot(from,from,4L));
        assertThat(read(from).complete()).isFalse();
        verify(bank,times(1)).historySnapshot(anyString(),anyString(),any(),any(),isNull());
    }
    @Test void unknownCountDoesNotRetryOrClaimEmptyComplete() {
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenReturn(snapshot(from,from.plusDays(2),null));
        assertThat(read(from.plusDays(2)).complete()).isFalse();
        verify(bank,times(1)).historySnapshot(anyString(),anyString(),any(),any(),isNull());
    }
    @Test void incompleteChildRemainsIncompleteAfterMerge() {
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenAnswer(i->{
            LocalDate start=i.getArgument(2),end=i.getArgument(3);
            return snapshot(start,end,start.equals(end)?null:2L);
        });
        assertThat(read(from.plusDays(1)).complete()).isFalse();
    }
    @Test void duplicateIdsAcrossChildrenAreRejected() {
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenAnswer(i->{
            LocalDate start=i.getArgument(2),end=i.getArgument(3);
            if(!start.equals(end)) return snapshot(start,end,2L);
            return new BankHistory(start,end,null,1L,List.of(new BankTransaction("duplicate",start,
                    LocalTime.NOON,BankDirection.DEPOSIT,10,100,"")),at);
        });
        assertThat(read(from.plusDays(1)).integrity()).isEqualTo(BankHistory.Integrity.DUPLICATE_TRANSACTION);
    }
    @Test void deadlinePreventsStartingBankCall() {
        when(cipher.decrypt(any(),any())).thenReturn("test-account");
        assertThatThrownBy(()->reader.read("test-key",account,from,from,System.nanoTime()-1))
                .isInstanceOf(FinApiException.class);
        verifyNoInteractions(bank);
    }
    @Test void monthlyWorstCaseIsBoundedToSixtyOneCalls() {
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenAnswer(i->
                snapshot(i.getArgument(2),i.getArgument(3),1L));
        assertThat(read(from.plusDays(30)).complete()).isFalse();
        verify(bank,times(61)).historySnapshot(anyString(),anyString(),any(),any(),isNull());
    }
}
