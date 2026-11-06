package com.yamu.be.payment.ledger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 원장의 한국 날짜 기준 값. 저장 시각은 UTC이고 사용일·사용 월·실적 월은 한국 날짜다. */
final class LedgerDates {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String MONTHLY = "MONTHLY";

    private LedgerDates() {}

    static LocalDate koreanDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
    }
}
