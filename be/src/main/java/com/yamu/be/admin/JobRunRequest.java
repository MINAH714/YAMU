package com.yamu.be.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 「[운영] 정기 작업 수동 실행」 요청 본문. 본문이 없어도 된다(전부 기본값).
 *
 * @param asOf   기준 시각(UTC, 예: {@code 2026-11-01T15:30:00Z}). 없으면 지금. 오늘(한국 날짜)까지만 받고 미래는 400
 *               {@code INVALID_REQUEST}({@code fieldErrors[].field=asOf}). 명세와 다름: 명세는 미래도 받는다({@code JobRunService})
 * @param userId 한 사용자만 처리. 없으면 전체
 * @param dryRun 대상만 세고 실행하지 않음. 없으면 false
 */
public record JobRunRequest(
        @Schema(description = "기준 시각(UTC). 없으면 지금. 오늘(한국 날짜)까지만 되고 미래는 400 INVALID_REQUEST",
                example = "2026-11-01T15:30:00Z") Instant asOf,
        @Schema(description = "한 사용자만 처리(시연 계정)", example = "7") Long userId,
        @Schema(description = "대상만 세고 실행하지 않음", example = "false") Boolean dryRun) {
}
