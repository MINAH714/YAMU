package com.yamu.be.global.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 영업일 계산기 구성. 공휴일 파일은 서버가 뜰 때 한 번 읽는다. */
@Slf4j
@Configuration
class CalendarConfig {

    @Bean
    EmbeddedHolidays embeddedHolidays(ObjectMapper objectMapper) throws IOException {
        EmbeddedHolidays holidays = EmbeddedHolidays.load(objectMapper,
                new PathMatchingResourcePatternResolver().getResources(EmbeddedHolidays.LOCATION));
        log.info("공휴일 데이터를 읽었습니다: {}년", holidays.years());
        return holidays;
    }

    @Bean
    BusinessDayCalendar businessDayCalendar(HolidaySource holidaySource) {
        return new KoreanBusinessDayCalendar(holidaySource);
    }
}
