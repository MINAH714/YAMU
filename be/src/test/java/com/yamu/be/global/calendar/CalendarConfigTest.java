package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 실행 환경에서 공휴일 파일을 읽지 못해도 서버는 뜨고, 영업일 계산만 멈추는지 본다. 날짜 옆 주석은 요일이다. */
@ExtendWith(OutputCaptureExtension.class)
class CalendarConfigTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void 내장_파일을_읽으면_공휴일로_계산한다() {
        EmbeddedHolidays holidays = CalendarConfig.loadHolidays(objectMapper, new PathMatchingResourcePatternResolver());

        assertThat(holidays.years()).contains(2026, 2027);
        assertThat(new KoreanBusinessDayCalendar(holidays).isBusinessDay(LocalDate.of(2026, 10, 9))).isFalse(); // 금, 한글날
    }

    @Test
    void 파일이_하나도_없으면_서버는_뜨고_평일_계산만_멈춘다(CapturedOutput output) {
        assertUnavailable(CalendarConfig.loadHolidays(objectMapper, returning()));
        assertThat(output.getAll()).contains("공휴일 데이터를 읽지 못했습니다");
    }

    @Test
    void 파일이_깨졌으면_서버는_뜨고_평일_계산만_멈춘다() {
        assertUnavailable(CalendarConfig.loadHolidays(objectMapper, returning(file("2026.json", "{\"year\":2026,"))));
    }

    @Test
    void 파일_목록을_읽지_못하면_서버는_뜨고_평일_계산만_멈춘다() {
        ResourcePatternResolver failing = new PathMatchingResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) throws IOException {
                throw new FileNotFoundException(locationPattern);
            }
        };
        assertUnavailable(CalendarConfig.loadHolidays(objectMapper, failing));
    }

    /** 빈 목록으로 대신 계산하지 않는다. 그러면 평일 공휴일이 영업일로 잘못 세진다. */
    private static void assertUnavailable(EmbeddedHolidays holidays) {
        BusinessDayCalendar calendar = new KoreanBusinessDayCalendar(holidays);

        assertThatThrownBy(() -> calendar.isBusinessDay(LocalDate.of(2026, 10, 9))) // 금, 한글날
                .isInstanceOf(HolidayDataUnavailableException.class);
        assertThatThrownBy(() -> calendar.businessDayOnOrAfter(LocalDate.of(2026, 10, 10))) // 토 → 월에서 멈춤
                .isInstanceOf(HolidayDataUnavailableException.class);
        assertThatThrownBy(() -> calendar.plusBusinessDays(LocalDate.of(2026, 10, 8), 1))
                .isInstanceOf(HolidayDataUnavailableException.class);
        // 토·일은 데이터 없이도 쉬는 날이 확실하다
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 10, 10))).isFalse(); // 토
    }

    private static ResourcePatternResolver returning(Resource... files) {
        return new PathMatchingResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) {
                return files;
            }
        };
    }

    private static Resource file(String name, String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }
}
