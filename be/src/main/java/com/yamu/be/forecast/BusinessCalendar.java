package com.yamu.be.forecast;

import java.time.LocalDate;

/**
 * 출금이 일어나는 영업일을 정한다. 영업일은 월~금에서 공휴일·임시공휴일을 뺀 날이다(결제 정책 회의).
 *
 * <p>계약만 있다. 공휴일을 어디서 어떻게 읽을지(외부 API 등)와 구현 주체는 아직 정하지 않았다(2026-11-01 기준).
 * 카드 청구 출금일(BE 2)과 같은 달력을 써야 같은 청구의 출금일이 화면마다 달라지지 않는다.
 * 구현할 때 결제 패키지 등 더 맞는 곳으로 옮겨도 된다.
 */
public interface BusinessCalendar {

    /**
     * date가 영업일이면 date, 아니면 그 뒤 첫 영업일을 돌려준다. date보다 앞선 날을 돌려주지 않는다.
     *
     * @param date 한국 날짜
     * @return date 이후(포함) 첫 영업일
     */
    LocalDate onOrAfter(LocalDate date);
}
