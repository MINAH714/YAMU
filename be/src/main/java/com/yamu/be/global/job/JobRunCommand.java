package com.yamu.be.global.job;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 정기 작업 한 번의 입력(「[운영] 정기 작업 수동 실행」 Request).
 *
 * <p>{@code asOf}는 대상을 고르는 기준(한국 날짜)에만 쓴다. 확정 시각·출금 시각·요청 번호 같은 기록은 실제 시계로 남긴다.
 * 과거로 돌려 다시 돌려도 기록 시각은 거짓이 되지 않게 하기 위해서다.
 *
 * <p>수동 실행은 미래 {@code asOf}(한국 날짜가 오늘보다 뒤)를 받지 않는다({@code admin.JobRunService}). 미래로 돌리면 이용기간이
 * 안 끝난 청구서도 확정돼 되돌릴 수 없기 때문이다. 명세는 미래도 받는다.
 *
 * @param asOf   기준 시각. 스케줄 실행은 지금
 * @param userId 한 사용자만 처리(시연 계정). null이면 전체
 * @param dryRun 대상만 세고 바꾸지 않음
 */
public record JobRunCommand(Instant asOf, Long userId, boolean dryRun) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public JobRunCommand {
        if (asOf == null) {
            throw new IllegalArgumentException("기준 시각이 없습니다");
        }
    }

    /** 스케줄 실행: 지금 기준, 전체 사용자, 실제 실행 */
    public static JobRunCommand now(Clock clock) {
        return new JobRunCommand(clock.instant(), null, false);
    }

    /** 기준 시각의 한국 날짜. 마감일·출금일은 한국 날짜로 비교한다. */
    public LocalDate koreanDate() {
        return LocalDate.ofInstant(asOf, KST);
    }
}
