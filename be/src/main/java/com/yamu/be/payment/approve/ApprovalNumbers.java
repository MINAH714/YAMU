package com.yamu.be.payment.approve;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 승인번호. 임시(명세 「결제 상태·예외 처리」 8장 Q3 제안): {@code Y} + 승인일(한국 날짜 8자리) + 결제 ID 10자리(0 채움).
 * 예: 2026-10-22 승인, 결제 1024 → {@code Y202610220000001024}. 결제 ID가 유일하므로 겹치지 않고, 저장하지 않고 계산한다.
 */
public final class ApprovalNumbers {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ApprovalNumbers() {}

    /** @param approvedAt 승인 시각(UTC, DB {@code approved_at}) */
    public static String of(LocalDateTime approvedAt, long paymentId) {
        String date = approvedAt.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).format(DATE);
        return "Y" + date + String.format("%010d", paymentId);
    }
}
