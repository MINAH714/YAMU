package com.yamu.be.global.calendar;

/**
 * 공휴일 API를 부르지 못했거나 응답을 믿을 수 없다. 받는 쪽은 그 해를 덮어쓰지 않고 기존 데이터를 그대로 쓴다.
 *
 * <p>원인 예외를 붙이지 않는다. RestClient 예외 메시지에는 서비스 키가 든 요청 주소가 그대로 들어가서,
 * 원인을 붙이면 스택 트레이스로 키가 로그에 남는다. 메시지에는 연도·월·결과 코드·예외 종류만 적는다.
 */
public class HolidayApiException extends RuntimeException {

    public HolidayApiException(String message) {
        super(message);
    }
}
