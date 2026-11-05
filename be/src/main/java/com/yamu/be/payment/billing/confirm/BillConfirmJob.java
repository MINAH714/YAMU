package com.yamu.be.payment.billing.confirm;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingProperties;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 청구 확정 정기 작업(Task 5.4.2, {@code bill-confirm}, 매일 00:30 한국 시간). 이용기간 마감이 기준일 전인 진행 중 청구서를
 * 번호 순으로 하나씩 {@link BillConfirmer}로 확정한다.
 *
 * <ul>
 *   <li>멱등: 확정은 청구서 잠금 안에서 상태를 다시 보므로 재실행·겹친 실행·수동 실행이 같은 청구서를 두 번 확정하지 않는다.
 *       같은 작업은 {@link JobLocks}로 한 번에 하나만 돈다.</li>
 *   <li>따라잡기: "어제가 마감"이 아니라 "마감이 지난" 전부를 보므로 서버가 멈춘 날의 청구서도 다음 실행에 확정된다.</li>
 *   <li>실패 격리: 한 청구서의 실패(청구할인 있음·데이터 이상)는 그 청구서만 롤백하고 결과에 FAILED로 남긴 뒤 다음으로 간다.
 *       계좌가 없는 청구서는 SKIPPED로 두고 다음 실행에 다시 본다.</li>
 * </ul>
 *
 * <p>스케줄은 {@code yamu.billing.jobs-enabled=true}일 때만 실제로 돈다(기본 끔). 수동 실행 API는 설정과 상관없이 돈다.
 */
@Slf4j
@Component
public class BillConfirmJob implements ManualJob {

    public static final String NAME = "bill-confirm";
    static final String REF_TYPE = "CARD_BILL";
    static final int PAGE_SIZE = 50;

    private final CardBillRepository bills;
    private final BillConfirmer confirmer;
    private final JobLocks jobLocks;
    private final BillingProperties properties;
    private final Clock clock;

    public BillConfirmJob(CardBillRepository bills, BillConfirmer confirmer, JobLocks jobLocks,
            BillingProperties properties, Clock clock) {
        this.bills = bills;
        this.confirmer = confirmer;
        this.jobLocks = jobLocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${yamu.billing.confirm-cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            JobRunReport report = jobLocks.locked(NAME, () -> run(JobRunCommand.now(clock)));
            log.info("청구 확정 작업 끝 targets={} succeeded={} failed={} skipped={}", report.targets(),
                    report.count(JobRunReport.Outcome.SUCCEEDED), report.count(JobRunReport.Outcome.FAILED),
                    report.count(JobRunReport.Outcome.SKIPPED));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.JOB_ALREADY_RUNNING) {
                throw e;
            }
            log.info("청구 확정 작업이 이미 돌고 있어 이번 회차는 건너뜀");
        }
    }

    /** 잠금 없이 한 번 돈다. 잠금은 부르는 쪽(스케줄·수동 실행)이 잡는다. */
    @Override
    public JobRunReport run(JobRunCommand command) {
        List<JobRunReport.Item> results = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<CardBill> page = bills.findDueForConfirm(BillStatus.ACCUMULATING, command.koreanDate(),
                    command.userId(), lastId, PageRequest.of(0, PAGE_SIZE));
            for (CardBill bill : page) {
                lastId = bill.getId();
                results.add(command.dryRun()
                        ? JobRunReport.Item.dryRun(REF_TYPE, bill.getId(), bill.getUserId(), null)
                        : confirmOne(bill));
            }
            if (page.size() < PAGE_SIZE) {
                return new JobRunReport(results);
            }
        }
    }

    private JobRunReport.Item confirmOne(CardBill bill) {
        try {
            BillConfirmer.Result result = confirmer.confirm(bill.getId(), nowUtc());
            return switch (result.kind()) {
                case CONFIRMED -> JobRunReport.Item.succeeded(REF_TYPE, result.cardBillId(), result.userId(),
                        result.status().name(), result.confirmedAmount(), null);
                case ALREADY_DONE -> JobRunReport.Item.skipped(REF_TYPE, result.cardBillId(), result.userId(),
                        "이미 확정된 청구서입니다(" + result.status() + ")");
                case NO_ACCOUNT -> JobRunReport.Item.skipped(REF_TYPE, result.cardBillId(), result.userId(),
                        "카드의 출금 계좌가 없어 확정하지 않았습니다. 다음 실행에 다시 확인합니다");
            };
        } catch (RuntimeException e) {
            log.error("청구 확정 실패, 운영 확인 cardBillId={}", bill.getId(), e);
            return JobRunReport.Item.failed(REF_TYPE, bill.getId(), bill.getUserId(),
                    "청구 확정에 실패했습니다(서버 로그 확인)");
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
