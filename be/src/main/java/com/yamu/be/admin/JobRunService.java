package com.yamu.be.admin;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
