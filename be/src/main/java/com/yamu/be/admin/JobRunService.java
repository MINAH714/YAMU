package com.yamu.be.admin;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 정기 작업 수동 실행(API 명세 「[운영] 정기 작업 수동 실행」). 등록된 {@link ManualJob}을 이름으로 찾아 작업 잠금 안에서 한 번 돌린다.
 * 스케줄 실행과 같은 잠금을 쓰므로 둘이 겹치면 뒤의 것이 409 {@code JOB_ALREADY_RUNNING}이다.
 *
 * <p>지금 등록된 작업: {@code bill-confirm}(Task 5.4.2). 명세 표의 나머지 작업은 각 Task에서 {@link ManualJob}으로 추가한다.
 */
@Slf4j
@Service
public class JobRunService {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Map<String, ManualJob> jobs;
    private final JobLocks jobLocks;
    private final Clock clock;

    public JobRunService(List<ManualJob> jobs, JobLocks jobLocks, Clock clock) {
        this.jobs = jobs.stream().collect(Collectors.toMap(ManualJob::name, Function.identity()));
        this.jobLocks = jobLocks;
        this.clock = clock;
    }

    public JobRunResponse run(String jobName, JobRunRequest request) {
        ManualJob job = jobs.get(jobName);
        if (job == null) {
            throw new BusinessException(ErrorCode.JOB_NOT_FOUND, Map.of("jobNames", jobs.keySet().stream().sorted().toList()));
        }
        LocalDateTime startedAt = nowUtc();
        Instant asOf = request != null && request.asOf() != null ? request.asOf() : clock.instant();
        JobRunCommand command = new JobRunCommand(asOf, request == null ? null : request.userId(),
                request != null && Boolean.TRUE.equals(request.dryRun()));
        rejectFuture(command);
        log.info("정기 작업 수동 실행 job={} asOf={} userId={} dryRun={}", jobName, asOf, command.userId(),
                command.dryRun());
        JobRunReport report = jobLocks.locked(jobName, () -> job.run(command));
        LocalDateTime finishedAt = nowUtc();
        log.info("정기 작업 수동 실행 끝 job={} targets={} failed={}", jobName, report.targets(),
                report.count(JobRunReport.Outcome.FAILED));
        return new JobRunResponse("run_" + startedAt.format(RUN_ID) + "_" + jobName, jobName,
                LocalDateTime.ofInstant(asOf, ZoneOffset.UTC), command.dryRun(), startedAt, finishedAt,
                JobRunResponse.Summary.of(report), report.results().stream().map(JobRunResponse.Result::of).toList());
    }

    /**
     * 미래 {@code asOf}(한국 날짜가 오늘보다 뒤)는 400으로 막는다. 미래로 돌리면 이용기간이 안 끝난 청구서도 확정되고 되돌릴 수 없으며,
     * 그 뒤 실제 마감일까지 그 카드로 같은 청구 월에 결제하면 422 {@code BILL_CLOSED}가 된다(MR !125 리뷰). 시연을 위해 이 결과를
     * 감수하지 않기로 했다(2026-11-05). 대상을 고르는 기준이 한국 날짜라 오늘 안의 뒤 시각은 지금과 결과가 같아 막지 않는다.
     * {@code dryRun}도 같은 규칙을 따른다.
     *
     * <p>명세와 다름: 「[운영] 정기 작업 수동 실행」은 "미래로 두면 그 날짜 기준으로 처리"다(노션 반영 필요).
     */
    private void rejectFuture(JobRunCommand command) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        if (command.koreanDate().isAfter(today)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.of(
                    new FieldErrorResponse("asOf", "기준 시각은 오늘(한국 날짜)까지만 쓸 수 있습니다."))));
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
