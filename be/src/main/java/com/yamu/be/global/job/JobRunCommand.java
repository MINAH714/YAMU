package com.yamu.be.global.job;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 정기 작업 한 번의 입력(「[운영] 정기 작업 수동 실행」 Request).
 *
 * <p>{@code asOf}는 대상을 고르는 기준(한국 날짜)에만 쓴다. 확정 시각·출금 시각·요청 번호 같은 기록은 실제 시계로 남긴다.
 * 시연에서 날짜를 건너뛰어도 기록 시각은 거짓이 되지 않게 하기 위해서다.
 *
 * <p>미래 {@code asOf}는 명세(「[운영] 정기 작업 수동 실행」 Request: "미래로 두면 그 날짜 기준으로 처리")대로 막지 않는다. 시연 순서
 * "결제 → 청구 확정(asOf 마감 다음 날) → 결제일 출금(asOf 결제일)"을 한 자리에서 보이려면 필요하다. 대신 바뀐 상태는 실제로 남는다.
 * <ul>
 *   <li>청구 확정은 이용기간이 아직 안 끝난 청구서도 확정한다. 할부는 승인 때 다음 달 회차까지 그 달 청구서에 붙여 두므로, 몇 달 뒤로
 *       돌리면 그 청구서들도 확정된다.</li>
 *   <li>그 뒤 실제 마감일까지 그 카드로 같은 청구 월에 신용 결제하면 회차를 붙일 청구서가 이미 확정돼 422 {@code reason=BILL_CLOSED}다
 *       ({@code CreditBillingWriter}). 시연은 결제를 모두 마친 뒤 확정하거나, 확정 뒤에는 다른 카드로 결제한다.</li>
 *   <li>운영 프로필이 따로 없어 운영 키({@code YAMU_ADMIN_KEY})를 설정한 서버라면 어디서든 돌릴 수 있다. 키가 비면 모두 403이다.</li>
 * </ul>
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
