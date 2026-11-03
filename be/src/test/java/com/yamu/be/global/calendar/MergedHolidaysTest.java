package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 그 해 API 목록이 있으면 그것, 없으면 내장 파일. 날짜 옆 주석은 요일이다. */
@ExtendWith(OutputCaptureExtension.class)
class MergedHolidaysTest {

    static final LocalDate LABOR_DAY = LocalDate.of(2026, 5, 1);        // 금 노동절
    static final LocalDate CHILDREN_DAY = LocalDate.of(2026, 5, 5);     // 화 어린이날
    static final LocalDate TEMPORARY = LocalDate.of(2026, 10, 2);       // 금 (가상의 임시공휴일)

    /** 2026년만 있는 내장 파일: 노동절·어린이날. */
    final EmbeddedHolidays embedded = EmbeddedHolidays.load(Jackson2ObjectMapperBuilder.json().build(), file("2026.json", """
            {"year": 2026, "source": "test", "holidays": [
              {"date": "2026-05-01", "name": "노동절"}, {"date": "2026-05-05", "name": "어린이날"}]}"""));

    @Test
    void API_목록이_없으면_내장_파일_그대로다() {
        MergedHolidays holidays = new MergedHolidays(embedded);

        assertThat(holidays.holidaysOf(2026)).contains(Set.of(LABOR_DAY, CHILDREN_DAY));
        assertThat(holidays.holidaysOf(2028)).isEmpty();
    }

    @Test
    void API_목록이_있는_해는_내장_파일과_섞지_않고_API_목록만_쓴다() {
        MergedHolidays holidays = new MergedHolidays(embedded);

        // API에 노동절이 빠지고 임시공휴일이 더해졌다고 둔다
        holidays.update(2026, Set.of(CHILDREN_DAY, TEMPORARY));

        assertThat(holidays.holidaysOf(2026)).contains(Set.of(CHILDREN_DAY, TEMPORARY));
    }

    @Test
    void 내장_파일이_없는_해는_API_목록만_쓴다() {
        MergedHolidays holidays = new MergedHolidays(embedded);
        LocalDate newYear2028 = LocalDate.of(2028, 1, 1);

        holidays.update(2028, Set.of(newYear2028));

        assertThat(holidays.holidaysOf(2028)).contains(Set.of(newYear2028));
        assertThat(holidays.syncedYears()).containsExactly(2028);
    }

    @Test
    void 다시_받으면_그_해_API_목록을_통째로_바꾼다() {
        MergedHolidays holidays = new MergedHolidays(embedded);
        holidays.update(2026, Set.of(TEMPORARY));

        holidays.update(2026, Set.of(CHILDREN_DAY));

        assertThat(holidays.holidaysOf(2026)).contains(Set.of(CHILDREN_DAY));
    }

    @Test
    void 내장_파일을_읽지_못했어도_API_목록이_있는_해는_센다() {
        MergedHolidays holidays = new MergedHolidays(EmbeddedHolidays.unavailable(new IOException("읽기 실패")));
        holidays.update(2026, Set.of(LABOR_DAY));

        assertThat(holidays.holidaysOf(2026)).contains(Set.of(LABOR_DAY));
        assertThatThrownBy(() -> holidays.holidaysOf(2027)).isInstanceOf(HolidayDataUnavailableException.class);
    }

    @Test
    void 내장_파일과_다른_날은_로그로_남긴다(CapturedOutput output) {
        MergedHolidays holidays = new MergedHolidays(embedded);

        holidays.update(2026, Set.of(CHILDREN_DAY, TEMPORARY));

        assertThat(output.getAll())
                .contains("2026년 공휴일을 API 목록으로 셉니다. 내장 파일과 다른 날: API에만 [2026-10-02], 내장 파일에만 [2026-05-01]");
    }

    @Test
    void 내장_파일과_같으면_로그를_남기지_않는다(CapturedOutput output) {
        MergedHolidays holidays = new MergedHolidays(embedded);

        holidays.update(2026, Set.of(LABOR_DAY, CHILDREN_DAY));

        assertThat(output.getAll()).doesNotContain("API 목록으로 셉니다");
    }

    @Test
    void 영업일_계산기는_동기화된_해를_API_목록으로_센다() {
        MergedHolidays holidays = new MergedHolidays(embedded);
        BusinessDayCalendar calendar = new KoreanBusinessDayCalendar(holidays);
        assertThat(calendar.isBusinessDay(TEMPORARY)).isTrue();
        assertThat(calendar.isBusinessDay(LABOR_DAY)).isFalse(); // 동기화 전에는 내장 파일

        holidays.update(2026, Set.of(TEMPORARY));

        assertThat(calendar.isBusinessDay(TEMPORARY)).isFalse();
        assertThat(calendar.isBusinessDay(LABOR_DAY)).isTrue(); // API에 없으면 영업일
        assertThat(calendar.businessDayOnOrAfter(TEMPORARY)).isEqualTo(LocalDate.of(2026, 10, 5)); // 월
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
