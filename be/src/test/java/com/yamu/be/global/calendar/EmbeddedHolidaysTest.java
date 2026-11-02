package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class EmbeddedHolidaysTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private EmbeddedHolidays bundled() throws IOException {
        return EmbeddedHolidays.load(objectMapper,
                new PathMatchingResourcePatternResolver().getResources(EmbeddedHolidays.LOCATION));
    }

    // ───────── 저장소에 넣은 파일 ─────────

    // 새 해의 파일을 넣으면 그 해의 날짜 목록·합계 테스트를 아래에 추가한다(docs/be/holiday.md). 있는 해의 테스트는 그대로 통과한다.

    @Test
    void 내장_파일에_2026년과_2027년이_있다() throws IOException {
        assertThat(bundled().years()).contains(2026, 2027);
    }

    @Test
    void 공휴일_2026년은_월력요항과_개정_규정의_날짜와_같다() throws IOException {
        assertThat(bundled().holidaysOf(2026).orElseThrow()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18), // 설
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2),    // 3·1절(일) + 대체
                LocalDate.of(2026, 5, 1),                              // 노동절(2026-05-01 시행)
                LocalDate.of(2026, 5, 5),
                LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 25),  // 부처님오신날(일) + 대체
                LocalDate.of(2026, 6, 3),                              // 전국동시지방선거
                LocalDate.of(2026, 6, 6),
                LocalDate.of(2026, 7, 17),                             // 제헌절
                LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 17),  // 광복절(토) + 대체
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26), // 추석
                LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5),  // 개천절(토) + 대체
                LocalDate.of(2026, 10, 9),
                LocalDate.of(2026, 12, 25));
    }

    @Test
    void 공휴일_2027년은_월력요항의_날짜와_같다() throws IOException {
        assertThat(bundled().holidaysOf(2027).orElseThrow()).containsExactlyInAnyOrder(
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 7), LocalDate.of(2027, 2, 8), // 설
                LocalDate.of(2027, 2, 9),                              // 설날(일) 대체
                LocalDate.of(2027, 3, 1),
                LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 3),    // 노동절(토) + 대체
                LocalDate.of(2027, 5, 5),
                LocalDate.of(2027, 5, 13),                             // 부처님오신날
                LocalDate.of(2027, 6, 6),
                LocalDate.of(2027, 7, 17), LocalDate.of(2027, 7, 19),  // 제헌절(토) + 대체
                LocalDate.of(2027, 8, 15), LocalDate.of(2027, 8, 16),  // 광복절(일) + 대체
                LocalDate.of(2027, 9, 14), LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 16), // 추석
                LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 4),  // 개천절(일) + 대체
                LocalDate.of(2027, 10, 9), LocalDate.of(2027, 10, 11), // 한글날(토) + 대체
                LocalDate.of(2027, 12, 25), LocalDate.of(2027, 12, 27)); // 기독탄신일(토) + 대체
    }

    @Test
    void 실질_공휴일이_해마다_72일이라_공식_발표와_맞는다() throws IOException {
        // 우주항공청 「2027년 월력요항」: 2026년·2027년 모두 실질 공휴일 72일 = 일요일 + 일요일이 아닌 공휴일
        EmbeddedHolidays holidays = bundled();
        for (int year : new int[] {2026, 2027}) {
            long sundays = LocalDate.of(year, 1, 1).datesUntil(LocalDate.of(year + 1, 1, 1))
                    .filter(d -> d.getDayOfWeek() == DayOfWeek.SUNDAY).count();
            Set<LocalDate> days = holidays.holidaysOf(year).orElseThrow();
            long notSunday = days.stream().filter(d -> d.getDayOfWeek() != DayOfWeek.SUNDAY).count();
            assertThat(sundays).as("%d년 일요일", year).isEqualTo(52);
            assertThat(notSunday).as("%d년 일요일이 아닌 공휴일", year).isEqualTo(20);
            assertThat(sundays + notSunday).as("%d년 실질 공휴일", year).isEqualTo(72);
        }
    }

    @Test
    void 연말_12월_31일은_공휴일이_아니다() throws IOException {
        // 임시(1차): 관공서 공휴일이 아니므로 영업일로 센다(Jira S15P31E103-210)
        assertThat(bundled().holidaysOf(2026).orElseThrow()).doesNotContain(LocalDate.of(2026, 12, 31));
    }

    @Test
    void 파일이_없는_해는_비어_있다() {
        EmbeddedHolidays only2026 = EmbeddedHolidays.load(objectMapper, file("2026.json", valid(2026)));
        assertThat(only2026.holidaysOf(2026)).isPresent();
        assertThat(only2026.holidaysOf(2027)).isEmpty();
    }

    // ───────── 잘못된 파일은 서버가 뜨지 않게 한다 ─────────

    @Test
    void 파일이_하나도_없으면_실패한다() {
        assertThatThrownBy(() -> EmbeddedHolidays.load(objectMapper)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 파일_이름이_연도가_아니면_실패한다() {
        assertInvalid(file("holidays.json", valid(2026)));
    }

    @Test
    void year가_파일_이름과_다르면_실패한다() {
        assertInvalid(file("2026.json", valid(2027)));
    }

    @Test
    void 다른_해의_날짜가_있으면_실패한다() {
        assertInvalid(file("2026.json", """
                {"year":2026,"holidays":[{"date":"2027-01-01","name":"1월 1일"}]}
                """));
    }

    @Test
    void 같은_날짜가_두_번_있으면_실패한다() {
        assertInvalid(file("2026.json", """
                {"year":2026,"holidays":[{"date":"2026-01-01","name":"1월 1일"},{"date":"2026-01-01","name":"신정"}]}
                """));
    }

    @Test
    void 목록이_비었거나_이름이_빠지면_실패한다() {
        assertInvalid(file("2026.json", "{\"year\":2026,\"holidays\":[]}"));
        assertInvalid(file("2026.json", "{\"year\":2026}"));
        assertInvalid(file("2026.json", "{\"year\":2026,\"holidays\":[{\"date\":\"2026-01-01\",\"name\":\" \"}]}"));
        assertInvalid(file("2026.json", "{\"year\":2026,\"holidays\":[{\"name\":\"1월 1일\"}]}"));
    }

    @Test
    void 달력에_없는_날짜나_깨진_JSON이면_실패한다() {
        assertInvalid(file("2026.json", "{\"year\":2026,\"holidays\":[{\"date\":\"2026-02-30\",\"name\":\"없는 날\"}]}"));
        assertInvalid(file("2026.json", "{\"year\":2026,"));
    }

    @Test
    void 같은_해_파일이_두_개면_실패한다() {
        assertThatThrownBy(() -> EmbeddedHolidays.load(objectMapper, file("2026.json", valid(2026)),
                file("2026.json", valid(2026))))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertInvalid(Resource file) {
        assertThatThrownBy(() -> EmbeddedHolidays.load(objectMapper, file)).isInstanceOf(IllegalStateException.class);
    }

    private static String valid(int year) {
        return "{\"year\":%d,\"holidays\":[{\"date\":\"%d-01-01\",\"name\":\"1월 1일\"}]}".formatted(year, year);
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
