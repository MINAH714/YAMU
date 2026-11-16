package com.yamu.be.assetflow;
import com.yamu.be.accounthistory.*;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.global.exception.*;
import com.yamu.be.payment.bank.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PersistedAssetFlowHistoryReaderTest {
    final AssetFlowHistoryReader upstream=mock(AssetFlowHistoryReader.class);
    final AccountHistoryStore store=mock(AccountHistoryStore.class);
    final PersistedAssetFlowHistoryReader reader=new PersistedAssetFlowHistoryReader(upstream,store);
    final LinkedAccount account=mock(LinkedAccount.class);
    final LocalDate day=LocalDate.of(2026,11,12);
    final AccountHistoryStore.Ticket ticket=new AccountHistoryStore.Ticket(1,2,3);
    PersistedAssetFlowHistoryReaderTest() {
        when(account.getId()).thenReturn(2L);when(account.getUserId()).thenReturn(7L);
        when(store.begin(7,2,day,day)).thenReturn(ticket);
    }
    @Test void externalFailureReturnsPersistedValuesWithFailureMetadata() {
        doThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT)).when(upstream).read("test",account,day,day,100);
        when(store.read(7,2,day,day)).thenReturn(new AccountHistoryStore.View(Map.of(day,new AccountHistoryStore.Day(42L,HistorySource.SSAFY)),"OK"));
        var result=reader.read("test",account,day,day,100);
        assertThat(result.days().get(day).balance()).isEqualTo(42);
        assertThat(result.reason()).isEqualTo("UPSTREAM_TIMEOUT");
        verify(store).failed(ticket,"UPSTREAM_TIMEOUT");
        verify(store,never()).complete(any(),any());
    }
    @Test void fullyVerifiedSyntheticRangeDoesNotInheritRealNoAnchorError() {
        var snapshot=new BankHistory(day,day,null,0L,List.of(),Instant.parse("2026-11-16T00:00:00Z"));
        when(upstream.read("test",account,day,day,100)).thenReturn(snapshot);
        when(store.complete(ticket,snapshot)).thenReturn("NO_ANCHOR");
        when(store.read(7,2,day,day)).thenReturn(new AccountHistoryStore.View(Map.of(day,new AccountHistoryStore.Day(0L,HistorySource.QA_SYNTHETIC)),"OK"));
        assertThat(reader.read("test",account,day,day,100).reason()).isEqualTo("OK");
    }
}
