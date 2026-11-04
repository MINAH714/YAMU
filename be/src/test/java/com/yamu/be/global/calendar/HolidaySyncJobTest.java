package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.yamu.be.global.redis.RedisLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.scheduling.TaskScheduler;

/** 동기화 작업이 언제 부르고, 실패하면 무엇을 남기지 않는지 본다. API·Redis는 가짜로 둔다. */
@ExtendWith(OutputCaptureExtension.class)
class HolidaySyncJobTest {

    /** UTC 11-02 19:00 = 한국 11-03 04:00. 대상은 2026·2027년이다. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T19:00:00Z"), ZoneOffset.UTC);
    static final Map<LocalDate, String> HOLIDAYS_2027 = Map.of(LocalDate.of(2027, 1, 1), "1월 1일");

    HolidayApiClient client;
    RedisHolidayStore store;
    MergedHolidays holidays;
    RedisLocks redisLocks;
    TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        client = mock(HolidayApiClient.class);
        store = mock(RedisHolidayStore.class);
        holidays = new MergedHolidays(EmbeddedHolidays.unavailable(new IllegalStateException("이 테스트는 내장 파일을 쓰지 않는다")));
        redisLocks = mock(RedisLocks.class);
        scheduler = mock(TaskScheduler.class);
        given(redisLocks.tryLock(HolidaySyncJob.LOCK_KEY, HolidaySyncJob.LOCK_TTL)).willReturn(Optional.of(mock(RedisLocks.Lock.class)));
    }

    @Test
    void 키가_없으면_아무것도_부르지_않는다(CapturedOutput output) {
        HolidaySyncJob job = job("");

        job.onReady();
        job.daily();

        verifyNoInteractions(scheduler, client, store, redisLocks);
        assertThat(output.getAll()).contains("공휴일 동기화를 하지 않습니다");
    }

    @Test
    void 서버가_뜨면_시작을_붙잡지_않고_스케줄러에서_돈다() {
        job("key").onReady();

        verify(scheduler).schedule(any(Runnable.class), eq(CLOCK.instant()));
        verifyNoInteractions(client);
    }

    @Test
    void 매일_올해와_내년을_받아_메모리와_Redis에_넣는다() {
        given(client.fetchYear(2026)).willReturn(Map.of(LocalDate.of(2026, 5, 5), "어린이날"));
        given(client.fetchYear(2027)).willReturn(HOLIDAYS_2027);

        job("key").daily();

        verify(store).replace(2026, Map.of(LocalDate.of(2026, 5, 5), "어린이날"), CLOCK.instant());
        verify(store).replace(2027, HOLIDAYS_2027, CLOCK.instant());
        assertThat(holidays.holidaysOf(2027)).contains(Set.of(LocalDate.of(2027, 1, 1)));
    }

    @Test
    void 시작할_때는_Redis에_있는_해를_읽고_동기화한_적_없는_해만_받는다() {
        given(store.find(2026)).willReturn(Optional.of(Map.of(LocalDate.of(2026, 5, 5), "어린이날")));
        given(store.syncedAt(2026)).willReturn(Optional.of(Instant.parse("2026-11-01T19:00:00Z")));
        given(store.find(2027)).willReturn(Optional.empty());
        given(store.syncedAt(2027)).willReturn(Optional.empty());
        given(client.fetchYear(2027)).willReturn(HOLIDAYS_2027);

        job("key").startup();

        verify(client, never()).fetchYear(2026);
        verify(store).replace(2027, HOLIDAYS_2027, CLOCK.instant());
        assertThat(holidays.holidaysOf(2026)).contains(Set.of(LocalDate.of(2026, 5, 5)));
        assertThat(holidays.syncedYears()).containsExactly(2026, 2027);
    }

    @Test
    void 시작할_때_모두_동기화돼_있으면_API를_부르지_않는다() {
        given(store.find(anyInt())).willReturn(Optional.empty());
        given(store.syncedAt(anyInt())).willReturn(Optional.of(Instant.parse("2026-11-01T19:00:00Z")));

        job("key").startup();

        verifyNoInteractions(client, redisLocks);
    }

    @Test
    void API가_실패한_해는_덮어쓰지_않고_다른_해는_계속한다(CapturedOutput output) {
        given(client.fetchYear(2026)).willThrow(new HolidayApiException("공휴일 API 실패 2026-03: HTTP 500"));
        given(client.fetchYear(2027)).willReturn(HOLIDAYS_2027);

        job("key").daily();

        verify(store, never()).replace(eq(2026), anyMap(), any());
        verify(store).replace(2027, HOLIDAYS_2027, CLOCK.instant());
        assertThat(holidays.syncedYears()).containsExactly(2027);
        assertThat(output.getAll()).contains("2026년 공휴일 동기화에 실패해 기존 데이터를 그대로 씁니다: 공휴일 API 실패 2026-03: HTTP 500");
    }

    @Test
    void 아직_발표_전이라_0건인_해는_저장하지_않는다(CapturedOutput output) {
        given(client.fetchYear(2026)).willReturn(Map.of(LocalDate.of(2026, 5, 5), "어린이날"));
        given(client.fetchYear(2027)).willReturn(Map.of());

        job("key").daily();

        verify(store, never()).replace(eq(2027), anyMap(), any());
        assertThat(holidays.syncedYears()).containsExactly(2026);
        assertThat(output.getAll()).contains("2027년 공휴일이 API에 아직 없습니다(발표 전)");
    }

    @Test
    void 다른_서버가_동기화_중이면_부르지_않고_잠금_TTL_뒤에_Redis에서_다시_읽는다() {
        given(redisLocks.tryLock(HolidaySyncJob.LOCK_KEY, HolidaySyncJob.LOCK_TTL)).willReturn(Optional.empty());

        job("key").daily();

        verifyNoInteractions(client, store);
        ArgumentCaptor<Runnable> reload = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(reload.capture(), eq(CLOCK.instant().plus(HolidaySyncJob.LOCK_TTL)));

        // 잠금을 잡은 서버가 그사이 Redis에 2027년을 넣었다
        given(store.find(2026)).willReturn(Optional.empty());
        given(store.find(2027)).willReturn(Optional.of(HOLIDAYS_2027));
        reload.getValue().run();

        verifyNoInteractions(client);
        assertThat(holidays.syncedYears()).containsExactly(2027);
        assertThat(holidays.holidaysOf(2027)).contains(Set.of(LocalDate.of(2027, 1, 1)));
    }

    @Test
    void 다시_읽다가_한_해에서_Redis가_실패해도_다른_해는_읽는다(CapturedOutput output) {
        given(store.find(2026)).willThrow(new RedisConnectionFailureException("down"));
        given(store.find(2027)).willReturn(Optional.of(HOLIDAYS_2027));

        job("key").reload(List.of(2026, 2027));

        assertThat(holidays.syncedYears()).containsExactly(2027);
        assertThat(output.getAll()).contains("2026년 공휴일을 Redis에서 다시 읽지 못해");
    }

    @Test
    void Redis에_저장하지_못해도_이_서버의_메모리에는_반영한다() {
        given(client.fetchYear(anyInt())).willReturn(HOLIDAYS_2027);
        willThrow(new RedisConnectionFailureException("down")).given(store).replace(anyInt(), anyMap(), any());

        job("key").sync(List.of(2027));

        assertThat(holidays.holidaysOf(2027)).contains(Set.of(LocalDate.of(2027, 1, 1)));
    }

    @Test
    void 시작할_때_Redis를_읽지_못하면_API도_부르지_않고_내장_파일로_센다(CapturedOutput output) {
        given(store.find(anyInt())).willThrow(new RedisConnectionFailureException("down"));

        job("key").startup();

        verifyNoInteractions(client);
        assertThat(output.getAll()).contains("Redis에서 읽지 못했습니다");
    }

    @Test
    void 시작할_때_한_해만_Redis를_읽지_못하면_다른_해는_읽고_동기화한_적_없으면_받는다() {
        given(store.find(2026)).willThrow(new RedisConnectionFailureException("down"));
        given(store.find(2027)).willReturn(Optional.empty());
        given(store.syncedAt(2027)).willReturn(Optional.empty());
        given(client.fetchYear(2027)).willReturn(HOLIDAYS_2027);

        job("key").startup();

        verify(client, never()).fetchYear(2026);
        verify(store).replace(2027, HOLIDAYS_2027, CLOCK.instant());
        assertThat(holidays.syncedYears()).containsExactly(2027);
    }

    private HolidaySyncJob job(String key) {
        HolidayApiProperties properties = new HolidayApiProperties("https://holiday.test", key, Duration.ofSeconds(3),
                Duration.ofSeconds(10), "0 0 4 * * *");
        return new HolidaySyncJob(client, store, holidays, redisLocks, properties, scheduler, CLOCK);
    }
}
