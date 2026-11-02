package com.yamu.be.global.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

/**
 * 저장소에 넣어 둔 연도별 공휴일 파일({@code resources/holidays/{연도}.json})을 읽는다.
 *
 * <p>파일이 하나도 없거나, 깨졌거나(형식 오류, 다른 해 날짜, 같은 날 두 번) 이름이 규칙과 다르면 {@link #load}가 실패한다.
 * 우리가 넣는 파일이라 빌드 테스트(EmbeddedHolidaysTest)가 실제 파일을 읽어 배포 전에 잡는다. 그래도 실행 환경에서 읽지 못하면
 * 서버는 뜨고 {@link #unavailable} 상태가 되어, 공휴일을 묻는 순간 {@link HolidayDataUnavailableException}을 던진다.
 * 파일이 없는 해는 오류가 아니다. 공휴일은 전년도 6월쯤(월력요항) 발표되므로 12개월 할부의 마지막 회차처럼
 * 아직 발표 전인 해를 묻는 일은 정상적으로 생긴다. 그 해는 주말만 쉬는 날로 계산된다({@link KoreanBusinessDayCalendar}).
 */
public final class EmbeddedHolidays implements HolidaySource {

    static final String LOCATION = "classpath:holidays/*.json";
    private static final Pattern FILE_NAME = Pattern.compile("(\\d{4})\\.json");

    private final Map<Integer, Set<LocalDate>> byYear;
    /** 읽지 못했을 때의 원인. 정상이면 null. */
    private final Exception failure;

    private EmbeddedHolidays(Map<Integer, Set<LocalDate>> byYear, Exception failure) {
        this.byYear = Map.copyOf(byYear);
        this.failure = failure;
    }

    /** 파일을 읽지 못한 상태. 공휴일을 물으면 {@link HolidayDataUnavailableException}. */
    static EmbeddedHolidays unavailable(Exception cause) {
        return new EmbeddedHolidays(Map.of(), Objects.requireNonNull(cause, "cause"));
    }

    /** 파일들을 읽어 검사한다. 하나라도 잘못되면 {@link IllegalStateException}. */
    static EmbeddedHolidays load(ObjectMapper objectMapper, Resource... files) {
        if (files.length == 0) {
            throw new IllegalStateException("공휴일 파일이 없습니다: " + LOCATION);
        }
        Map<Integer, Set<LocalDate>> byYear = new TreeMap<>();
        for (Resource file : files) {
            String name = file.getFilename();
            Matcher matcher = name == null ? null : FILE_NAME.matcher(name);
            if (matcher == null || !matcher.matches()) {
                throw new IllegalStateException("공휴일 파일 이름은 {연도}.json이어야 합니다: " + name);
            }
            int year = Integer.parseInt(matcher.group(1));
            if (byYear.containsKey(year)) {
                throw new IllegalStateException("같은 해의 공휴일 파일이 두 개입니다: " + name);
            }
            byYear.put(year, validate(year, name, read(objectMapper, file, name)));
        }
        return new EmbeddedHolidays(byYear, null);
    }

    @Override
    public Optional<Set<LocalDate>> holidaysOf(int year) {
        if (failure != null) {
            throw new HolidayDataUnavailableException(failure);
        }
        return Optional.ofNullable(byYear.get(year));
    }

    /** 데이터가 있는 해들. 오름차순. 읽지 못한 상태면 비어 있다. */
    Set<Integer> years() {
        return new TreeSet<>(byYear.keySet());
    }

    private static HolidayFile read(ObjectMapper objectMapper, Resource file, String name) {
        try (InputStream in = file.getInputStream()) {
            return objectMapper.readValue(in, HolidayFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("공휴일 파일을 읽지 못했습니다: " + name, e);
        }
    }

    private static Set<LocalDate> validate(int year, String name, HolidayFile file) {
        if (file == null || file.year() == null || file.year() != year) {
            throw new IllegalStateException("공휴일 파일의 year가 파일 이름과 다릅니다: " + name);
        }
        if (file.holidays() == null || file.holidays().isEmpty()) {
            throw new IllegalStateException("공휴일 목록이 비어 있습니다: " + name);
        }
        Set<LocalDate> dates = new HashSet<>();
        for (Holiday holiday : file.holidays()) {
            if (holiday == null || holiday.date() == null || holiday.name() == null || holiday.name().isBlank()) {
                throw new IllegalStateException("날짜나 이름이 빠진 공휴일이 있습니다: " + name);
            }
            if (holiday.date().getYear() != year) {
                throw new IllegalStateException("다른 해의 날짜가 들어 있습니다: " + name + " " + holiday.date());
            }
            if (!dates.add(holiday.date())) {
                throw new IllegalStateException("같은 날짜가 두 번 들어 있습니다: " + name + " " + holiday.date());
            }
        }
        return Set.copyOf(dates);
    }

    /**
     * 파일 모양. source는 사람이 보는 출처 기록이라 읽기만 하고 쓰지 않는다.
     *
     * @param holidays 일요일과 겹친 공휴일도 넣는다(달력과 같게 두어 맞는지 확인하기 쉽게)
     */
    record HolidayFile(Integer year, String source, List<Holiday> holidays) {
    }

    record Holiday(LocalDate date, String name) {
    }
}
