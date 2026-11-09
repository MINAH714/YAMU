package com.yamu.be.payment.approve;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 승인 시각(UTC)에서 한국 날짜 기준 값을 뽑는다. 실적 월·청구 월은 한국 날짜가 기준이다(API 명세 1장). */
final class ApprovalDates {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private ApprovalDates() {}

    /** 승인 시각의 한국 날짜. */
    static LocalDate koreanDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
    }

    /** 실적을 합산할 월: 승인 시각의 한국 날짜가 속한 달의 1일. */
    static LocalDate performanceMonth(LocalDateTime utc) {
        return koreanDate(utc).withDayOfMonth(1);
    }
}
