package com.yamu.be.admin;

import com.yamu.be.global.job.JobRunReport;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 「[운영] 정기 작업 수동 실행」 응답. 시각은 UTC({@code Z})로 나간다.
 *
 * @param jobRunId 이 실행의 이름(로그 대조용). {@code run_yyyyMMdd_HHmmss_작업이름}
 */
public record JobRunResponse(String jobRunId, String jobName, LocalDateTime asOf, boolean dryRun,
        LocalDateTime startedAt, LocalDateTime finishedAt, Summary summary, List<Result> results) {

    public record Summary(int targets, long succeeded, long failed, long skipped) {

        static Summary of(JobRunReport report) {
            return new Summary(report.targets(), report.count(JobRunReport.Outcome.SUCCEEDED),
                    report.count(JobRunReport.Outcome.FAILED), report.count(JobRunReport.Outcome.SKIPPED));
        }
    }

    public record Result(String refType, Long refId, Long userId, String result, Long amount, String finTransactionNo,
            String message) {

        static Result of(JobRunReport.Item item) {
            return new Result(item.refType(), item.refId(), item.userId(), item.result(), item.amount(),
                    item.finTransactionNo(), item.message());
        }
    }
}
