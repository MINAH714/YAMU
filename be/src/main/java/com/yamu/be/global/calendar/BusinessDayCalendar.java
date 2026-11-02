package com.yamu.be.global.calendar;

import java.time.LocalDate;

/**
 * 한국 영업일 계산. 영업일은 토·일·공휴일이 아닌 날이다(10/21 결제 정책 회의).
 * 날짜만 답한다(이 날이 영업일인가, 다음 영업일은 언제인가). 청구 출금일(결제일이 비영업일이면 다음 영업일,
 * POL-005·FR-067)·할부 회차 출금일·환급 기한은 청구 쪽이 이 계산기로 정한다. 잔고예보는 이미 생긴 청구면 청구 쪽
 * withdrawalDate를 그대로 읽고, 아직 청구가 없는 가상 구매·예정 지출만 이 계산기로 센다.
 * 같은 출금일이 화면마다 달라지지 않게 하기 위해서다.
 *
 * <p>날짜는 모두 한국 날짜다. UTC 시각에서 날짜를 뽑을 때는 {@code ZoneId.of("Asia/Seoul")}로 바꾼 뒤 넘긴다.
 * 공휴일 데이터가 없는 해는 주말만 쉬는 날로 계산한다. 공휴일 파일을 읽지 못했으면 평일이 걸린 계산은
 * {@link HolidayDataUnavailableException}으로 멈춘다. 사용법·데이터 갱신: docs/be/holiday.md
 */
public interface BusinessDayCalendar {

    /** 토·일·공휴일이 아니면 true. */
    boolean isBusinessDay(LocalDate date);

    /**
     * date가 영업일이면 그대로, 아니면 그 뒤 첫 영업일.
     * 예: 결제일이 2026-09-24(추석 연휴)면 2026-09-28(월).
     */
    LocalDate businessDayOnOrAfter(LocalDate date);

    /**
     * date 다음 날부터 영업일을 하나씩 세어 days번째 영업일. date 자신은 세지 않는다.
     * 예: 금요일 + 1영업일 = 다음 주 월요일, 토요일 + 1영업일 = 월요일.
     *
     * @param days 1 이상 366 이하
     */
    LocalDate plusBusinessDays(LocalDate date, int days);
}
