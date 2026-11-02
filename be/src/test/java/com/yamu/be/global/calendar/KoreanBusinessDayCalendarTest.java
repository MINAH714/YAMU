package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 저장소에 넣은 실제 공휴일 파일로 계산한다. 날짜 옆 주석은 요일이다. */
@ExtendWith(OutputCaptureExtension.class)
class KoreanBusinessDayCalendarTest {

    BusinessDayCalendar calendar;

    @BeforeEach
    void setUp() throws IOException {
        EmbeddedHolidays holidays = EmbeddedHolidays.load(Jackson2ObjectMapperBuilder.json().build(),
                new PathMatchingResourcePatternResolver().getResources(EmbeddedHolidays.LOCATION));
        calendar = new KoreanBusinessDayCalendar(holidays);
    }

    // ───────── 영업일 여부 ─────────

    @Test
    void 평일은_영업일이고_토요일과_일요일은_아니다() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 11, 2))).isTrue();  // 월
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 11, 6))).isTrue();  // 금
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 11, 7))).isFalse(); // 토
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 11, 8))).isFalse(); // 일
    }

    @Test
    void 평일_공휴일은_영업일이_아니다() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 10, 9))).isFalse(); // 금 한글날
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 6, 3))).isFalse();  // 수 지방선거
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 5, 1))).isFalse();  // 금 노동절
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 7, 17))).isFalse(); // 금 제헌절
    }

    @Test
    void 연말_12월_31일은_영업일이다() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 12, 31))).isTrue(); // 목
    }

    // ───────── 결제일이 비영업일이면 다음 영업일 ─────────

    @Test
    void 영업일이면_그날_그대로다() {
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2026, 11, 2))).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void 주말이면_월요일이다() {
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2026, 11, 7))).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void 대체공휴일까지_건너뛴다() {
        // 8/15(토) 광복절 → 8/16(일) → 8/17(월) 대체공휴일 → 8/18(화)
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2026, 8, 15))).isEqualTo(LocalDate.of(2026, 8, 18));
        // 10/3(토) 개천절 → 10/5(월) 대체공휴일 → 10/6(화)
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2026, 10, 3))).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void 명절_연휴와_주말이_이어지면_연휴가_끝난_뒤다() {
        // 9/24(목)~9/26(토) 추석, 9/27(일) → 9/28(월)
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2026, 9, 24))).isEqualTo(LocalDate.of(2026, 9, 28));
        // 2027 설: 2/6(토)~2/8(월), 2/9(화) 대체공휴일 → 2/10(수)
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2027, 2, 6))).isEqualTo(LocalDate.of(2027, 2, 10));
    }

    @Test
    void 연말에서_새해로_넘어간다() {
        // 2026-12-26(토) → 12/28(월)
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2026, 12, 26))).isEqualTo(LocalDate.of(2026, 12, 28));
        // 2027-01-01(금) 1월 1일 → 1/4(월)
        assertThat(calendar.businessDayOnOrAfter(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    // ───────── n영업일 뒤 ─────────

    @Test
    void 기준일은_세지_않고_다음_날부터_센다() {
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 11, 2), 3)).isEqualTo(LocalDate.of(2026, 11, 5)); // 월 → 목
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 11, 6), 1)).isEqualTo(LocalDate.of(2026, 11, 9)); // 금 → 월
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 11, 7), 1)).isEqualTo(LocalDate.of(2026, 11, 9)); // 토 → 월
    }

    @Test
    void 공휴일과_연휴는_세지_않는다() {
        // 9/23(수) + 3영업일: 9/24~9/27 연휴·주말 → 9/28(월), 9/29(화), 9/30(수)
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 9, 23), 3)).isEqualTo(LocalDate.of(2026, 9, 30));
        // 12/30(수) + 3영업일: 12/31(목), 1/1 쉼, 1/4(월), 1/5(화)
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 12, 30), 3)).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void 영업일_수가_범위를_벗어나거나_날짜가_없으면_계산하지_않는다() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        assertThatThrownBy(() -> calendar.plusBusinessDays(day, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.plusBusinessDays(day, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.plusBusinessDays(day, 367)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.plusBusinessDays(null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.isBusinessDay(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calendar.businessDayOnOrAfter(null)).isInstanceOf(NullPointerException.class);
    }

    // ───────── 데이터가 없거나 잘못된 경우 ─────────

    @Test
    void 공휴일_데이터가_없는_해는_주말만_쉬고_경고는_해마다_한_번이다(CapturedOutput output) {
        // 내장 파일에 새 해가 늘어도 깨지지 않게, 2026년만 아는 소스로 아직 발표 전인 해를 흉내 낸다
        HolidaySource only2026 = year -> year == 2026 ? Optional.of(Set.of(LocalDate.of(2026, 1, 1))) : Optional.empty();
        BusinessDayCalendar calendar = new KoreanBusinessDayCalendar(only2026);

        // 2027-03-01(월) 3·1절이지만 2027년 데이터가 없어 영업일로 계산된다
        assertThat(calendar.isBusinessDay(LocalDate.of(2027, 3, 1))).isTrue();
        assertThat(calendar.isBusinessDay(LocalDate.of(2027, 3, 6))).isFalse(); // 토
        assertThat(calendar.plusBusinessDays(LocalDate.of(2027, 3, 5), 1)).isEqualTo(LocalDate.of(2027, 3, 8));

        String warning = "2027년 공휴일 데이터가 없어";
        assertThat(output.getAll()).contains(warning);
        assertThat(output.getAll().split(warning, -1)).hasSize(2);
    }

    @Test
    void 쉬는_날이_한_달_넘게_이어지면_데이터가_잘못된_것으로_보고_멈춘다() {
        HolidaySource everyDayOff = year -> Optional.of(allDaysOf(year));
        BusinessDayCalendar broken = new KoreanBusinessDayCalendar(everyDayOff);

        assertThatThrownBy(() -> broken.businessDayOnOrAfter(LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Set<LocalDate> allDaysOf(int year) {
        return LocalDate.of(year, 1, 1).datesUntil(LocalDate.of(year + 1, 1, 1)).collect(Collectors.toSet());
    }
}
