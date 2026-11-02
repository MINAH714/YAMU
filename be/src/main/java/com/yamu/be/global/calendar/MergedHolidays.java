package com.yamu.be.global.calendar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;

/**
 * 공휴일 API로 받은 목록과 내장 파일을 묶은 공휴일. 영업일 계산기는 이것을 읽는다.
 *
 * <p>API 목록이 기준이다. 그 해 API 목록이 있으면 그것만 쓰고, 없을 때(동기화 전·실패·발표 전)만 내장 파일을 쓴다.
 * 내장 파일은 API를 붙이기 전에 임시로 넣은 데이터라 API와 섞지 않는다.
 *
 * <p>영업일 계산은 날짜마다 이 목록을 묻는다. 그래서 Redis를 매번 읽지 않고 메모리 사본만 읽으며,
 * 동기화가 끝나거나 서버가 뜰 때 Redis에서 읽은 값으로 {@link #update}한다.
 */
@Slf4j
public class MergedHolidays implements HolidaySource {

    private final EmbeddedHolidays embedded;
    /** API로 받은 목록. 해 단위로 통째 바꾸고, 읽는 쪽은 바꾸는 중인 맵을 보지 않는다. */
    private volatile Map<Integer, Set<LocalDate>> synced = Map.of();

    public MergedHolidays(EmbeddedHolidays embedded) {
        this.embedded = embedded;
    }

    /**
     * 그 해 API 목록이 있으면 그것, 없으면 내장 파일. 둘 다 없는 해는 비어 있다.
     *
     * @throws HolidayDataUnavailableException API 목록이 없는 해인데 내장 파일도 읽지 못했을 때
     */
    @Override
    public Optional<Set<LocalDate>> holidaysOf(int year) {
        Set<LocalDate> fromApi = synced.get(year);
        if (fromApi != null) {
            return Optional.of(fromApi);
        }
        return embedded.holidaysOf(year);
    }

    /** 그 해 API 목록을 바꾼다. 내장 파일과 다른 날이 있으면 로그로 남긴다(파일이 낡았는지 사람이 볼 수 있게). */
    public synchronized void update(int year, Set<LocalDate> fromApi) {
        logDifference(year, fromApi);
        Map<Integer, Set<LocalDate>> next = new HashMap<>(synced);
        next.put(year, Set.copyOf(fromApi));
        synced = Map.copyOf(next);
    }

    /** API 목록이 들어 있는 해들. 오름차순. */
    Set<Integer> syncedYears() {
        return new TreeSet<>(synced.keySet());
    }

    private void logDifference(int year, Set<LocalDate> fromApi) {
        Optional<Set<LocalDate>> fromFile;
        try {
            fromFile = embedded.holidaysOf(year);
        } catch (HolidayDataUnavailableException e) {
            return;
        }
        if (fromFile.isEmpty()) {
            return;
        }
        Set<LocalDate> added = new TreeSet<>(fromApi);
        added.removeAll(fromFile.get());
        Set<LocalDate> removed = new TreeSet<>(fromFile.get());
        removed.removeAll(fromApi);
        if (!added.isEmpty() || !removed.isEmpty()) {
            log.info("{}년 공휴일을 API 목록으로 셉니다. 내장 파일과 다른 날: API에만 {}, 내장 파일에만 {}", year, added, removed);
        }
    }
}
