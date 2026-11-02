package com.yamu.be.global.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/** 영업일 계산기 구성. 공휴일 파일은 서버가 뜰 때 한 번 읽는다. */
@Slf4j
@Configuration
class CalendarConfig {

    @Bean
    EmbeddedHolidays embeddedHolidays(ObjectMapper objectMapper) {
        return loadHolidays(objectMapper, new PathMatchingResourcePatternResolver());
    }

    /** 그 해 공휴일 API 목록이 있으면 그것, 없으면 내장 파일. API 목록은 동기화(HolidaySyncJob)가 채운다. */
    @Bean
    MergedHolidays mergedHolidays(EmbeddedHolidays embeddedHolidays) {
        return new MergedHolidays(embeddedHolidays);
    }

    @Bean
    BusinessDayCalendar businessDayCalendar(MergedHolidays mergedHolidays) {
        return new KoreanBusinessDayCalendar(mergedHolidays);
    }

    /**
     * 공휴일 파일을 읽는다. 읽지 못해도 서버는 띄우고, 영업일 계산이 필요한 작업만 멈추게 한다.
     * 파일 오류는 빌드 테스트에서 먼저 잡으므로 여기까지 오는 것은 실행 환경 문제다(docs/be/holiday.md).
     */
    static EmbeddedHolidays loadHolidays(ObjectMapper objectMapper, ResourcePatternResolver resolver) {
        try {
            EmbeddedHolidays holidays = EmbeddedHolidays.load(objectMapper, resolver.getResources(EmbeddedHolidays.LOCATION));
            log.info("공휴일 데이터를 읽었습니다: {}년", holidays.years());
            return holidays;
        } catch (IOException | IllegalStateException e) {
            log.error("공휴일 데이터를 읽지 못했습니다. 영업일 계산이 필요한 작업은 실패합니다(docs/be/holiday.md)", e);
            return EmbeddedHolidays.unavailable(e);
        }
    }
}
