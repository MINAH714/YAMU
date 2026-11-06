package com.yamu.be.payment.billing.refund;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import com.yamu.be.payment.billing.BillingProperties;
import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import com.yamu.be.payment.billing.RefundStatus;
import com.yamu.be.payment.billing.refund.RefundDepositor.Outcome;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 신용카드 취소 환급 입금 정기 작업(Task 5.4.4, {@code refund-payout}, 매일 09:00 한국 시간). 기한이 정해진 대기 환급을 번호 순으로
 * 50건씩 훑어 {@link RefundDepositor}로 한 건씩 입금한다.
 *
 * <ul>
 *   <li>대상: 기한을 기다리지 않고 기한이 정해진 환급을 모두 보낸다(기한 안에 들어갈 가능성을 높인다). 그래서 {@code asOf}는 대상을
 *       고르는 데 쓰지 않는다. 지난 날짜로 돌려도 같은 환급을 보내고, 결과 기록은 실제 시계로 남는다.</li>
 *   <li>멱등: 환급마다 요청 번호·거래내역 태그·행 잠금으로 한 번만 입금한다({@link RefundDepositor}). 같은 작업은 {@link JobLocks}로
 *       한 번에 하나만 돈다. 잠금 TTL이 지나 겹쳐도 환급 단위로 막힌다.</li>
 *   <li>실패 격리: 한 건의 예외(금융망 조회·채번 실패)는 결과에 {@code ERROR}로 남기고 다음 환급으로 간다. 아무것도 바꾸지 않았으니
 *       다음 실행이 다시 본다.</li>
 * </ul>
 *
 * <p>결과 이름({@code result}): {@code COMPLETED}(입금했거나 이미 들어간 입금을 찾음)·{@code FAILED}(은행 거절로 환급 실패)·
 * {@code NEEDS_REVIEW}(자동으로 판단하지 않고 대기로 둠)·{@code ERROR}는 집계상 실패, {@code UNKNOWN}·{@code NOT_SENT}·
 * {@code IN_FLIGHT}·{@code CONTENDED}·{@code SKIPPED}·{@code DRY_RUN}은 건너뜀이다.
 *
 * <p>스케줄은 {@code yamu.billing.jobs-enabled=true}일 때만 실제로 돈다(기본 끔). 수동 실행 API는 설정과 상관없이 돈다.
 */
@Slf4j
@Component
public class RefundPayoutJob implements ManualJob {

    public static final String NAME = "refund-payout";
    static final String REF_TYPE = "REFUND";
    static final int PAGE_SIZE = 50;

    private final RefundRepository refunds;
    private final RefundDepositor depositor;
    private final JobLocks jobLocks;
    private final BillingProperties properties;
    private final Clock clock;

    public RefundPayoutJob(RefundRepository refunds, RefundDepositor depositor, JobLocks jobLocks,
            BillingProperties properties, Clock clock) {
        this.refunds = refunds;
        this.depositor = depositor;
        this.jobLocks = jobLocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${yamu.billing.refund-payout-cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            JobRunReport report = jobLocks.locked(NAME, () -> run(JobRunCommand.now(clock)));
            log.info("환급 입금 작업 끝 targets={} succeeded={} failed={} skipped={}", report.targets(),
                    report.count(JobRunReport.Outcome.SUCCEEDED), report.count(JobRunReport.Outcome.FAILED),
                    report.count(JobRunReport.Outcome.SKIPPED));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.JOB_ALREADY_RUNNING) {
                throw e;
            }
            log.info("환급 입금 작업이 이미 돌고 있어 이번 회차는 건너뜀");
        }
    }

    /** 잠금 없이 한 번 돈다. 잠금은 부르는 쪽(스케줄·수동 실행)이 잡는다. */
    @Override
    public JobRunReport run(JobRunCommand command) {
        List<JobRunReport.Item> results = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<Refund> page = refunds.findPayoutTargets(RefundStatus.PENDING, command.userId(), lastId,
                    PageRequest.of(0, PAGE_SIZE));
            for (Refund refund : page) {
                lastId = refund.getId();
                results.add(command.dryRun()
                        ? JobRunReport.Item.dryRun(REF_TYPE, refund.getId(), refund.getUserId(), refund.getRefundAmount())
                        : payOne(refund));
            }
            if (page.size() < PAGE_SIZE) {
                return new JobRunReport(results);
            }
        }
    }

    private JobRunReport.Item payOne(Refund refund) {
        Long id = refund.getId();
        Long userId = refund.getUserId();
        long amount = refund.getRefundAmount();
        Outcome outcome;
        try {
            outcome = depositor.deposit(id);
        } catch (RuntimeException e) {
            log.warn("환급 입금 실패, 다음 실행에 다시 refundId={}", id, e);
            return item(id, userId, JobRunReport.Outcome.FAILED, "ERROR", null, null,
                    "처리 중 오류가 났습니다. 다음 실행에 다시 봅니다(서버 로그 확인)");
        }
        return switch (outcome) {
            case COMPLETED -> JobRunReport.Item.succeeded(REF_TYPE, id, userId, outcome.name(), amount,
                    refunds.findById(id).map(Refund::getFinTransactionNo).orElse(null));
            case FAILED -> item(id, userId, JobRunReport.Outcome.FAILED, outcome.name(), amount, null,
                    "은행이 입금을 거절해 환급을 실패로 끝냈습니다");
            case NEEDS_REVIEW -> item(id, userId, JobRunReport.Outcome.FAILED, outcome.name(), amount, null,
                    "자동으로 판단하지 않고 대기로 두었습니다. 운영 확인이 필요합니다(서버 로그 확인)");
            case UNKNOWN -> item(id, userId, JobRunReport.Outcome.SKIPPED, outcome.name(), amount, null,
                    "입금 결과를 모릅니다. 유예가 지난 뒤 다음 실행에 거래내역으로 확인합니다");
            case NOT_SENT -> item(id, userId, JobRunReport.Outcome.SKIPPED, outcome.name(), amount, null,
                    "입금 요청이 금융망에 가지 않았습니다. 다음 실행에 다시 보냅니다");
            case IN_FLIGHT -> item(id, userId, JobRunReport.Outcome.SKIPPED, outcome.name(), amount, null,
                    "직전 입금 요청의 결과를 기다리는 중입니다");
            case CONTENDED -> item(id, userId, JobRunReport.Outcome.SKIPPED, outcome.name(), amount, null,
                    "다른 실행이 먼저 처리해 보내지 않았습니다");
            case SKIPPED -> JobRunReport.Item.skipped(REF_TYPE, id, userId, "입금할 환급이 아닙니다");
        };
    }

    private static JobRunReport.Item item(Long refundId, Long userId, JobRunReport.Outcome outcome, String result,
            Long amount, String finTransactionNo, String message) {
        return new JobRunReport.Item(REF_TYPE, refundId, userId, outcome, result, amount, finTransactionNo, message);
    }
}
