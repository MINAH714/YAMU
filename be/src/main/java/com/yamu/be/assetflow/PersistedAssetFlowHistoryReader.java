package com.yamu.be.assetflow;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.accounthistory.*;
import com.yamu.be.global.exception.BusinessException;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/** Fetch outside transactions, validate/persist, then always render the database evidence. */
@Service
public class PersistedAssetFlowHistoryReader {
    private final AssetFlowHistoryReader upstream;
    private final AccountHistoryStore store;
    public PersistedAssetFlowHistoryReader(AssetFlowHistoryReader upstream,AccountHistoryStore store) {
        this.upstream=upstream; this.store=store;
    }
    /** Operator current-day capture. Its validated snapshot is excluded from daily closing-balance views. */
    public String captureCurrentSnapshot(String key,LinkedAccount account,java.time.Clock clock,long deadline) {
        LocalDate today=LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        var ticket=store.begin(account.getUserId(),account.getId(),today.withDayOfMonth(1),today);
        try {
            return store.completeCurrentSnapshot(ticket,upstream.read(key,account,today.withDayOfMonth(1),today,deadline));
        } catch(RuntimeException failure) {
            store.failed(ticket,"CURRENT_SNAPSHOT_FAILED"); throw failure;
        }
    }

    public AccountHistoryStore.View read(String key,LinkedAccount account,LocalDate from,LocalDate to,long deadline) {
        var ticket=store.begin(account.getUserId(),account.getId(),from,to);
        String reason;
        try {
            reason=store.complete(ticket,upstream.read(key,account,from,to,deadline));
        } catch(BusinessException failure) {
            reason=failure.getErrorCode().name();
            store.failed(ticket,reason);
            if(!reason.equals("FIN_API_ERROR")&&!reason.equals("FIN_API_UNAVAILABLE")&&!reason.equals("UPSTREAM_TIMEOUT")) throw failure;
        } catch(RuntimeException failure) {
            store.failed(ticket,"INTERNAL_ERROR"); throw failure;
        }
        var view=store.read(account.getUserId(),account.getId(),from,to);
        if (view.reason().equals("OK") && view.days().values().stream().allMatch(d -> d.source() == HistorySource.QA_SYNTHETIC)) return view;
        return new AccountHistoryStore.View(view.days(),reason.equals("OK")?view.reason():reason);
    }
}
