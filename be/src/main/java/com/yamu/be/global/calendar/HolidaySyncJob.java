package com.yamu.be.global.calendar;

import com.yamu.be.global.redis.RedisLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 공휴일 API 동기화. 대상은 올해와 내년(한국 날짜 기준)이다.
 *
 * <ul>
 *   <li>서버가 다 뜬 뒤: Redis에 있는 목록을 메모리로 읽고, 동기화한 적 없는 해만 API로 받는다. 최대 24번 호출이라
 *       서버 시작을 붙잡지 않도록 스케줄러 스레드에서 돈다.</li>
 *   <li>매일 04:00(한국): 두 해를 다시 받는다. 청구 확정(00:30) 뒤, 청구 출금(07:00) 전이다.</li>
 * </ul>
 *
 * <p>실패해도 서버와 영업일 계산은 그대로다. 한 달이라도 실패하면 그 해는 덮어쓰지 않고, 바로 다시 부르지 않고 다음 날을 기다린다.
 * 0건인 해는 아직 발표 전(월력요항은 전년도 6월쯤)으로 보고 저장하지 않는다. 서비스 키가 없으면 아무것도 하지 않고 내장 파일로만 센다.
 * 여러 서버가 같은 Redis를 쓰면 {@link RedisLocks}로 하나만 부른다.
 *
 * <p>예외는 종류와 {@link HolidayApiException}의 메시지만 남긴다. 다른 예외의 메시지·스택에 키가 든 주소가 섞일 수 있어서다.
 */
@Slf4j
public class HolidaySyncJob {

    static final String LOCK_KEY = "holiday:sync:lock";
    /** 24번 × 응답 제한 10초보다 길게. */
    static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HolidayApiClient client;
    private final RedisHolidayStore store;
    private final MergedHolidays holidays;
    private final RedisLocks redisLocks;
    private final HolidayApiProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public HolidaySyncJob(HolidayApiClient client, RedisHolidayStore store, MergedHolidays holidays, RedisLocks redisLocks,
            HolidayApiProperties properties, TaskScheduler scheduler, Clock clock) {
        this.client = client;
        this.store = store;
        this.holidays = holidays;
        this.redisLocks = redisLocks;
        this.properties = properties;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.hasServiceKey()) {
            log.warn("yamu.holiday.service-key가 비어 있어 공휴일 동기화를 하지 않습니다. 내장 파일로만 영업일을 셉니다"
                    + "(DATA_GO_KR_SERVICE_KEY 환경변수 또는 application-local.yml, docs/be/holiday.md)");
            return;
        }
        scheduler.schedule(this::startup, clock.instant());
    }

    @Scheduled(cron = "${yamu.holiday.sync-cron}", zone = "Asia/Seoul")
    public void daily() {
        if (!properties.hasServiceKey()) {
            return;
        }
        sync(targetYears());
    }

    /** Redis의 목록을 메모리로 읽고, 동기화한 적 없는 해만 받는다. */
    void startup() {
        List<Integer> neverSynced = new ArrayList<>();
        for (int year : targetYears()) {
            try {
                store.find(year).ifPresent(found -> holidays.update(year, found.keySet()));
                if (store.syncedAt(year).isEmpty()) {
                    neverSynced.add(year);
                }
            } catch (RuntimeException e) {
                log.error("{}년 공휴일을 Redis에서 읽지 못했습니다. 내장 파일로 셉니다: {}", year, e.getClass().getSimpleName());
                return;
            }
        }
        if (!neverSynced.isEmpty()) {
            sync(neverSynced);
        }
    }

    void sync(List<Integer> years) {
        Optional<RedisLocks.Lock> lock;
        try {
            lock = redisLocks.tryLock(LOCK_KEY, LOCK_TTL);
        } catch (RuntimeException e) {
            log.error("공휴일 동기화 잠금을 잡지 못했습니다(Redis): {}", e.getClass().getSimpleName());
            return;
        }
        if (lock.isEmpty()) {
            log.info("다른 서버가 공휴일을 동기화하고 있어 건너뜁니다");
            return;
        }
        try (RedisLocks.Lock held = lock.get()) {
            years.forEach(this::syncYear);
        } catch (RuntimeException e) {
            // 잠금을 풀다가 Redis가 끊긴 경우. 잠금은 TTL이 지나면 풀린다
            log.warn("공휴일 동기화 잠금을 풀지 못했습니다: {}", e.getClass().getSimpleName());
        }
    }

    private void syncYear(int year) {
        Map<LocalDate, String> fetched;
        try {
            fetched = client.fetchYear(year);
        } catch (HolidayApiException e) {
            log.warn("{}년 공휴일 동기화에 실패해 기존 데이터를 그대로 씁니다: {}", year, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("{}년 공휴일 동기화 중 예상하지 못한 오류로 기존 데이터를 그대로 씁니다: {}", year, e.getClass().getName());
            return;
        }
        if (fetched.isEmpty()) {
            log.info("{}년 공휴일이 API에 아직 없습니다(발표 전). 저장하지 않습니다", year);
            return;
        }
        holidays.update(year, fetched.keySet());
        try {
            store.replace(year, fetched, clock.instant());
        } catch (RuntimeException e) {
            // 이 서버의 메모리에는 반영됐다. Redis에는 다음 동기화 때 다시 저장한다
            log.error("{}년 공휴일을 Redis에 저장하지 못했습니다: {}", year, e.getClass().getSimpleName());
            return;
        }
        log.info("{}년 공휴일 {}일을 동기화했습니다", year, fetched.size());
    }

    private List<Integer> targetYears() {
        int thisYear = LocalDate.now(clock.withZone(SEOUL)).getYear();
        return List.of(thisYear, thisYear + 1);
    }
}
