package com.yamu.be.global.calendar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;

/**
 * 내장 파일과 공휴일 API로 받은 목록을 합친(합집합) 공휴일. 영업일 계산기는 이것을 읽는다.
 *
 * <p>합집합인 이유: 노동절·제헌절(2026년부터 관공서 공휴일)을 API가 반영하는지 아직 모른다. API 목록으로 통째 바꾸면
 * 빠진 날이 영업일이 되어 공휴일에 출금일이 잡힌다. 합치면 API에만 있는 임시공휴일은 더해지고 내장 파일의 날은 그대로 쉬는 날이다.
 * 한계: 지정이 취소된 공휴일은 API로 지울 수 없어서 내장 파일을 직접 고친다. 틀려도 출금이 하루 늦어지는 쪽이다.
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
     * 내장 파일 ∪ API 목록. 둘 다 없는 해는 비어 있다.
     *
     * @throws HolidayDataUnavailableException 내장 파일을 읽지 못했고 그 해 API 목록도 없을 때
     */
    @Override
    public Optional<Set<LocalDate>> holidaysOf(int year) {
        Set<LocalDate> fromApi = synced.get(year);
        Optional<Set<LocalDate>> fromFile;
        try {
            fromFile = embedded.holidaysOf(year);
        } catch (HolidayDataUnavailableException e) {
            // 내장 파일을 못 읽었어도 그 해 API 목록이 있으면 그것만으로 센다. 없으면 그대로 멈춘다
            if (fromApi == null) {
                throw e;
            }
            return Optional.of(fromApi);
        }
        if (fromApi == null) {
            return fromFile;
        }
        if (fromFile.isEmpty()) {
            return Optional.of(fromApi);
        }
        Set<LocalDate> union = new HashSet<>(fromFile.get());
        union.addAll(fromApi);
        return Optional.of(Set.copyOf(union));
    }

    /** 그 해 API 목록을 바꾼다. 내장 파일과 다른 날이 있으면 로그로 남긴다(파일을 고칠지 사람이 본다). */
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
        Set<LocalDate> onlyApi = new TreeSet<>(fromApi);
        onlyApi.removeAll(fromFile.get());
        Set<LocalDate> onlyFile = new TreeSet<>(fromFile.get());
        onlyFile.removeAll(fromApi);
        if (!onlyApi.isEmpty()) {
            log.info("{}년 공휴일 API에만 있는 날을 쉬는 날로 더합니다: {}", year, onlyApi);
        }
        if (!onlyFile.isEmpty()) {
            log.warn("{}년 내장 파일에만 있는 공휴일이 있습니다. 쉬는 날로 그대로 두며, 지정이 취소된 날이면 파일을 고칩니다: {}",
                    year, onlyFile);
        }
    }
}
