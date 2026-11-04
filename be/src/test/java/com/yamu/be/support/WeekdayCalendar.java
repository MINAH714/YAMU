package com.yamu.be.support;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** 주말만 비영업일로 보는 달력. 공휴일 자료 없이 영업일 보정을 확인하는 단위 테스트에 쓴다. */
public class WeekdayCalendar implements BusinessDayCalendar {

    @Override
    public boolean isBusinessDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    @Override
    public LocalDate businessDayOnOrAfter(LocalDate date) {
        LocalDate day = date;
        while (!isBusinessDay(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    @Override
    public LocalDate plusBusinessDays(LocalDate date, int days) {
        LocalDate day = date;
        int left = days;
        while (left > 0) {
            day = day.plusDays(1);
            if (isBusinessDay(day)) {
                left--;
            }
        }
        return day;
    }
}
