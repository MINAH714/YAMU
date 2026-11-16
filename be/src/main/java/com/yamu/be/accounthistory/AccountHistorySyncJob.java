package com.yamu.be.accounthistory;
import com.yamu.be.account.domain.*;
import com.yamu.be.assetflow.PersistedAssetFlowHistoryReader;
import com.yamu.be.global.crypto.*;
import com.yamu.be.global.job.*;
import com.yamu.be.global.exception.*;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Existing operator job endpoint supports dryRun and user filtering. Scheduler activation belongs to infrastructure. */
@Component
public class AccountHistorySyncJob implements ManualJob {
    private final LinkedAccountRepository accounts;
    private final AppUserRepository users;
    private final PersonalDataCipher cipher;
    private final PersistedAssetFlowHistoryReader history;
    private final JobLocks locks;
    private final Clock clock;
    private final boolean enabled;
    public AccountHistorySyncJob(LinkedAccountRepository accounts,AppUserRepository users,PersonalDataCipher cipher,
            PersistedAssetFlowHistoryReader history,JobLocks locks,Clock clock,
            @Value("${yamu.account-history.sync.enabled:false}") boolean enabled) {
        this.accounts=accounts;this.users=users;this.cipher=cipher;this.history=history;this.locks=locks;this.clock=clock;this.enabled=enabled;
    }
    public String name() { return "account-history-sync"; }
    @Scheduled(cron="${yamu.account-history.sync.cron:0 15 3 * * *}",zone="Asia/Seoul")
    public void scheduled() {
        if(!enabled) return;
        try { locks.locked(name(),()->run(JobRunCommand.now(clock))); }
        catch(BusinessException failure) { if(failure.getErrorCode()!=ErrorCode.JOB_ALREADY_RUNNING) throw failure; }
    }
    public JobRunReport run(JobRunCommand command) {
        var result=new ArrayList<JobRunReport.Item>();
        LocalDate to=command.koreanDate().minusDays(1);
        // Revalidate the displayed past horizon. Each request remains one month, using the existing split reader.
        for(int page=0;;page++) {
            var batch=accounts.findAll(PageRequest.of(page,100));
            for(var account:batch) {
                if(!account.isConnected() || (command.userId()!=null && !command.userId().equals(account.getUserId()))) continue;
                if(command.dryRun()) { result.add(JobRunReport.Item.dryRun("LINKED_ACCOUNT",account.getId(),account.getUserId(),null));continue; }
                try {
                    var user=users.findById(account.getUserId()).orElseThrow();
                    String key=cipher.decrypt(user.getSsafyUserKey(),CipherField.APP_USER_SSAFY_USER_KEY);
                    String reason="OK";
                    for(int offset=11;offset>=0;offset--) {
                        var month=YearMonth.from(command.koreanDate()).minusMonths(offset);
                        LocalDate end=month.atEndOfMonth().isBefore(to)?month.atEndOfMonth():to;
                        if(end.isBefore(month.atDay(1))) continue;
                        var view=history.read(key,account,month.atDay(1),end,System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
                        if(!view.reason().equals("OK")&&!view.reason().equals("NO_ANCHOR")) reason=view.reason();
                    }
                    result.add(reason.equals("OK")?JobRunReport.Item.succeeded("LINKED_ACCOUNT",account.getId(),account.getUserId(),"SYNCED",null,null)
                        :JobRunReport.Item.failed("LINKED_ACCOUNT",account.getId(),account.getUserId(),reason));
                } catch(RuntimeException failure) {
                    result.add(JobRunReport.Item.failed("LINKED_ACCOUNT",account.getId(),account.getUserId(),"HISTORY_SYNC_FAILED"));
                }
            }
            if(!batch.hasNext()) break;
        }
        return new JobRunReport(result);
    }
}
