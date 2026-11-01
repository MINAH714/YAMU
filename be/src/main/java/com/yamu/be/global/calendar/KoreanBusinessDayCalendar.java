package com.yamu.be.global.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 토·일과 {@link HolidaySource}의 공휴일을 쉬는 날로 보는 영업일 계산기.
 *
 * <p>임시(1차): 근로자의 날·12월 31일처럼 관공서 공휴일이 아닌 날은 영업일로 센다. 노동절(5월 1일)은
 * 2026년부터 관공서 공휴일이므로 공휴일 파일에 들어 있고 쉬는 날이다.
 */
@Slf4j
public class KoreanBusinessDayCalendar implements BusinessDayCalendar {

    /** n영업일 뒤 계산에서 받는 최대 n. 1년보다 먼 계산은 쓰는 곳이 없다. */
    static final int MAX_BUSINESS_DAYS = 366;
    /** 쉬는 날이 이보다 길게 이어지면 데이터가 잘못된 것이다. 실제 최장 연휴는 열흘(2017년 추석)이다. */
    private static final int MAX_CONSECUTIVE_DAYS_OFF = 31;

    private final HolidaySource holidays;
    /** 데이터 없는 해의 경고는 해마다 한 번만 남긴다. 날짜마다 남기면 정기 작업 로그가 덮인다. */
    private final Set<Integer> warnedYears = ConcurrentHashMap.newKeySet();

    public KoreanBusinessDayCalendar(HolidaySource holidays) {
        this.holidays = holidays;
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        Optional<Set<LocalDate>> ofYear = holidays.holidaysOf(date.getYear());
        if (ofYear.isEmpty()) {
            if (warnedYears.add(date.getYear())) {
                log.warn("{}년 공휴일 데이터가 없어 주말만 쉬는 날로 계산합니다(docs/be/holiday.md)", date.getYear());
            }
            return true;
        }
        return !ofYear.get().contains(date);
    }

    @Override
    public LocalDate businessDayOnOrAfter(LocalDate date) {
        LocalDate day = Objects.requireNonNull(date, "date");
        for (int i = 0; i <= MAX_CONSECUTIVE_DAYS_OFF; i++) {
            if (isBusinessDay(day)) {
                return day;
            }
            day = day.plusDays(1);
        }
        throw new IllegalStateException(date + "부터 " + MAX_CONSECUTIVE_DAYS_OFF + "일 넘게 영업일이 없습니다. 공휴일 데이터를 확인하세요");
    }

    @Override
    public LocalDate plusBusinessDays(LocalDate date, int days) {
        Objects.requireNonNull(date, "date");
        if (days < 1 || days > MAX_BUSINESS_DAYS) {
            throw new IllegalArgumentException("영업일 수는 1 이상 " + MAX_BUSINESS_DAYS + " 이하여야 합니다: " + days);
        }
        LocalDate day = date;
        for (int counted = 0; counted < days; counted++) {
            day = businessDayOnOrAfter(day.plusDays(1));
        }
        return day;
    }
}
