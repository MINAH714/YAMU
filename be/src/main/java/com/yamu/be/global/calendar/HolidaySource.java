package com.yamu.be.global.calendar;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * 연도별 공휴일 목록을 준다. 영업일 계산기는 내장 파일({@link EmbeddedHolidays})과 공공데이터 API로 받은 목록을
 * 합친 {@link MergedHolidays}를 읽는다.
 */
public interface HolidaySource {

    /**
     * 그 해의 공휴일(일요일과 겹친 날 포함). 그 해 데이터가 없으면 비어 있다.
     *
     * @throws HolidayDataUnavailableException 공휴일 데이터를 읽지 못했을 때
     */
    Optional<Set<LocalDate>> holidaysOf(int year);
}
