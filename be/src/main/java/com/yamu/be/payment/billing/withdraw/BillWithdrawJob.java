package com.yamu.be.payment.billing.withdraw;

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제일 청구 출금 정기 작업(Task 5.4.3, {@code bill-withdraw}, 매일 07:00 한국 시간). 출금 예정일이 기준일 이하인 확정 청구서를 번호
 * 순으로 하나씩 {@link BillWithdrawer}로 출금한다. 출금을 요청했지만 결과를 모르는 청구서도 같이 확인한다.
 *
 * <ul>
 *   <li>결과 불명 복구: 07:00과 별도로 {@code withdraw-recovery-interval}(10분)마다 요청 번호가 있는 청구서만 확인·재시도한다. 새 출금은
 *       하지 않는다. 같은 작업 잠금을 쓰므로 07:00 실행·수동 실행과 겹치지 않는다.</li>
 *   <li>멱등: 출금 전에 거래내역의 YAMU-B 태그를 보고, 요청 번호 선점을 청구서 잠금 안에서 하므로 재실행·겹친 실행·수동 실행이 같은 청구서를
 *       두 번 출금하지 않는다.</li>
 *   <li>따라잡기: "오늘이 출금일"이 아니라 "출금일이 지난" 전부를 보므로 서버가 멈춘 날의 청구서도 다음 실행에 출금한다.</li>
 *   <li>실패 격리: 한 청구서의 예외는 결과에 FAILED로 남기고 다음으로 간다.</li>
 * </ul>
 *
 * <p>결과 이름: 출금 성공 {@code PAID}, 은행 거절 {@code UNPAID}(출금 전 취소로 남은 미납액이 0원이면 {@code CANCELED})는 처리를 끝낸 것이라
 * succeeded로 센다. 결과 불명·대기·미룸은 skipped, 운영 확인이 필요한 것과 조회 실패는 failed다.
 * 스케줄은 {@code yamu.billing.jobs-enabled=true}일 때만 실제로 돈다(기본 끔). 수동 실행 API는 설정과 상관없이 돈다.
 */
@Slf4j
@Component
public class BillWithdrawJob implements ManualJob {

    public static final String NAME = "bill-withdraw";
    static final String REF_TYPE = "CARD_BILL";
    static final int PAGE_SIZE = 50;

    private final CardBillRepository bills;
    private final BillWithdrawer withdrawer;
    private final JobLocks jobLocks;
    private final BillingProperties properties;
    private final Clock clock;

    public BillWithdrawJob(CardBillRepository bills, BillWithdrawer withdrawer, JobLocks jobLocks,
            BillingProperties properties, Clock clock) {
        this.bills = bills;
        this.withdrawer = withdrawer;
        this.jobLocks = jobLocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${yamu.billing.withdraw-cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (properties.jobsEnabled()) {
            runLocked("청구 출금", () -> run(JobRunCommand.now(clock)));
        }
    }

    @Scheduled(fixedDelayString = "${yamu.billing.withdraw-recovery-interval}",
            initialDelayString = "${yamu.billing.withdraw-recovery-interval}")
    public void scheduledRecovery() {
        if (properties.jobsEnabled()) {
            runLocked("청구 출금 결과 확인", this::recoverInFlight);
        }
    }

    /** 잠금 없이 한 번 돈다. 잠금은 부르는 쪽(스케줄·수동 실행)이 잡는다. */
    @Override
    public JobRunReport run(JobRunCommand command) {
        List<JobRunReport.Item> results = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<CardBill> page = bills.findDueForWithdraw(BillStatus.CONFIRMED, command.koreanDate(),
                    command.userId(), lastId, PageRequest.of(0, PAGE_SIZE));
            for (CardBill bill : page) {
                lastId = bill.getId();
                results.add(command.dryRun()
                        ? JobRunReport.Item.dryRun(REF_TYPE, bill.getId(), bill.getUserId(), bill.getConfirmedAmount())
                        : withdrawOne(bill, command, false));
            }
            if (page.size() < PAGE_SIZE) {
                return new JobRunReport(results);
            }
        }
    }

    /** 결과 불명 복구: 출금을 요청한 확정 청구서만 확인·재시도한다. 잠금 없이 돈다. */
    JobRunReport recoverInFlight() {
        JobRunCommand command = JobRunCommand.now(clock);
        List<JobRunReport.Item> results = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<CardBill> page = bills.findWithdrawalsInFlight(BillStatus.CONFIRMED, lastId,
                    PageRequest.of(0, PAGE_SIZE));
            for (CardBill bill : page) {
                lastId = bill.getId();
                results.add(withdrawOne(bill, command, true));
            }
            if (page.size() < PAGE_SIZE) {
                return new JobRunReport(results);
            }
        }
    }

    private JobRunReport.Item withdrawOne(CardBill bill, JobRunCommand command, boolean inFlightOnly) {
        try {
            return toItem(withdrawer.withdraw(bill.getId(), command.koreanDate(), inFlightOnly));
        } catch (RuntimeException e) {
            log.error("청구 출금 처리 실패, 운영 확인 cardBillId={}", bill.getId(), e);
            return JobRunReport.Item.failed(REF_TYPE, bill.getId(), bill.getUserId(),
                    "청구 출금 처리에 실패했습니다(서버 로그 확인)");
        }
    }

    private static JobRunReport.Item toItem(BillWithdrawer.Result r) {
        return switch (r.kind()) {
            case PAID -> JobRunReport.Item.succeeded(REF_TYPE, r.cardBillId(), r.userId(), "PAID", r.amount(),
                    r.finTransactionNo());
            case UNPAID -> new JobRunReport.Item(REF_TYPE, r.cardBillId(), r.userId(), JobRunReport.Outcome.SUCCEEDED,
                    r.status().name(), r.amount(), null, r.message());
            case UNKNOWN, NOT_SENT, IN_FLIGHT, POSTPONED -> new JobRunReport.Item(REF_TYPE, r.cardBillId(), r.userId(),
                    JobRunReport.Outcome.SKIPPED, r.kind().name(), r.amount(), null, r.message());
            case CONTENDED, SKIPPED -> JobRunReport.Item.skipped(REF_TYPE, r.cardBillId(), r.userId(), r.message());
            case LOOKUP_FAILED, NEEDS_REVIEW -> JobRunReport.Item.failed(REF_TYPE, r.cardBillId(), r.userId(),
                    r.message());
        };
    }

    private void runLocked(String what, Supplier<JobRunReport> work) {
        try {
            JobRunReport report = jobLocks.locked(NAME, work);
            log.info("{} 작업 끝 targets={} succeeded={} failed={} skipped={}", what, report.targets(),
                    report.count(JobRunReport.Outcome.SUCCEEDED), report.count(JobRunReport.Outcome.FAILED),
                    report.count(JobRunReport.Outcome.SKIPPED));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.JOB_ALREADY_RUNNING) {
                throw e;
            }
            log.info("{} 작업이 이미 돌고 있어 이번 회차는 건너뜀", what);
        }
    }
}
