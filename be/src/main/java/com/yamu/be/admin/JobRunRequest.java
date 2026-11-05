package com.yamu.be.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 「[운영] 정기 작업 수동 실행」 요청 본문. 본문이 없어도 된다(전부 기본값).
 *
 * @param asOf   기준 시각(UTC, 예: {@code 2026-11-15T22:00:00Z}). 없으면 지금. 명세대로 미래 시각도 받는다(시연용 날짜 건너뛰기).
 *               미래로 돌린 결과는 실제로 남는다: {@code bill-confirm}은 이용기간이 아직 안 끝난 청구서도 확정하고(할부의 다음 달
 *               청구서 포함), 그 뒤 실제 마감일까지 그 카드로 같은 청구 월에 신용 결제하면 422 {@code reason=BILL_CLOSED}다
 *               ({@link com.yamu.be.global.job.JobRunCommand})
 * @param userId 한 사용자만 처리. 없으면 전체. 임시(명세에 없음): 미래 asOf(한국 날짜가 오늘보다 뒤)로 실제 실행할 때는 꼭 보내야
 *               하고, 없으면 400 {@code INVALID_REQUEST}({@code fieldErrors[].field=userId}). dryRun이면 없어도 된다
 * @param dryRun 대상만 세고 실행하지 않음. 없으면 false
 */
public record JobRunRequest(
        @Schema(description = "기준 시각(UTC). 없으면 지금. 미래로 두면 그 날짜 기준으로 처리하고 결과는 되돌리지 않는다"
                + "(bill-confirm은 이용기간이 안 끝난 청구서도 확정해, 실제 마감일까지 그 카드·그 청구 월 신용 결제는 422 BILL_CLOSED)",
                example = "2026-11-15T22:00:00Z") Instant asOf,
        @Schema(description = "한 사용자만 처리(시연 계정). 미래 asOf로 실제 실행할 때는 필수(없으면 400 INVALID_REQUEST)",
                example = "7") Long userId,
        @Schema(description = "대상만 세고 실행하지 않음", example = "false") Boolean dryRun) {
}
