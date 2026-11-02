package com.yamu.be.global.calendar;

/**
 * 공휴일 파일을 읽지 못해 영업일을 계산할 수 없다. 서버는 뜨지만 영업일이 필요한 작업은 이 예외로 멈춘다.
 * 빈 목록으로 대신 계산하면 평일에 낀 공휴일을 영업일로 잘못 세기 때문이다(docs/be/holiday.md).
 */
public class HolidayDataUnavailableException extends IllegalStateException {

    public HolidayDataUnavailableException(Throwable cause) {
        super("공휴일 데이터를 읽지 못해 영업일을 계산할 수 없습니다(docs/be/holiday.md): " + cause.getMessage(), cause);
    }
}
