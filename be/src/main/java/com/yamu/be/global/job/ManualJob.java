package com.yamu.be.global.job;

/**
 * 운영 API(「[운영] 정기 작업 수동 실행」)로 즉시 돌릴 수 있는 정기 작업. 스케줄러가 부르는 시각과 상관없이 기준 시각({@code asOf})을
 * 받아 "그날이라면" 처리할 대상을 처리한다. 이미 처리된 대상은 상태로 걸러 다시 처리하지 않는다(멱등).
 *
 * <p>구현은 같은 작업이 겹쳐 돌지 않게 {@link JobLocks} 안에서 불린다(수동 실행은 {@code admin.JobRunService}, 스케줄은 구현 자신이 잡는다).
 * 대상 하나의 실패는 보고서의 {@code FAILED} 한 줄로 남기고 나머지는 계속 처리한다.
 */
public interface ManualJob {

    /** 운영 API의 {@code jobName}. 소문자·하이픈(예: {@code bill-confirm}) */
    String name();

    JobRunReport run(JobRunCommand command);
}
