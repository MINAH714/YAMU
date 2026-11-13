package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.performance.PerformanceShortfallCheck;
import com.yamu.be.performance.PerformanceShortfallCheck.Shortfall;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실적 부족 알림 작업(Task 9.3.3)의 기간·시각·반복 키·건너뛰기·실패 격리. 실적 판정·DB·Redis는 대역이다. */
class PerformanceShortfallNotifyJobTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 30일인 달의 25일: 5일 남아 기간 안. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    static final long USER = 7L;
    static final long OTHER = 8L;

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NotificationSettingsStore settings = mock(NotificationSettingsStore.class);
    NotificationWriter writer = mock(NotificationWriter.class);
    PerformanceShortfallCheck shortfallCheck = mock(PerformanceShortfallCheck.class);
    PerformanceShortfallMarks marks = mock(PerformanceShortfallMarks.class);

    PerformanceShortfallNotifyJob job;

    @BeforeEach
    void setUp() {
        job = jobAt(at(TODAY, 10, 0));
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(USER));
        when(settings.read(anyLong())).thenReturn(NotificationSettings.DEFAULTS);
        when(writer.write(any())).thenReturn(NotificationWriter.Result.CREATED);
    }

    // ───────── 기간: 이번 달이 7일 이하로 남았을 때 ─────────

    @Test
    void 이번_달이_7일_이하로_남은_날부터_판정한다() {
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2026, 11, 22))).isFalse();   // 30일 달, 8일 남음
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2026, 11, 23))).isTrue();
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2026, 11, 30))).isTrue();    // 말일
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2026, 12, 23))).isFalse();   // 31일 달
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2026, 12, 24))).isTrue();
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2027, 2, 20))).isFalse();    // 28일 달
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2027, 2, 21))).isTrue();
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2028, 2, 21))).isFalse();    // 윤년 29일 달
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2028, 2, 22))).isTrue();
        assertThat(PerformanceShortfallNotifyJob.inWindow(LocalDate.of(2026, 12, 1))).isFalse();    // 다음 달 1일
    }

    @Test
    void 기간_전에는_사용자를_읽지도_않는다() {
        JobRunReport report = job.runDue(at(LocalDate.of(2026, 11, 22), 9, 0));

        assertThat(report.targets()).isZero();
        verifyNoInteractions(jdbc, settings, shortfallCheck, marks, writer);
    }

    @Test
    void 기간_첫날_한국_자정_뒤에는_판정한다() {
        when(settings.read(USER)).thenReturn(settingsAt(LocalTime.MIDNIGHT));
        shortfalls(shortfall(1L, false));

        // 11/23 00:00 KST = 11/22 15:00 UTC. 날짜를 UTC로 뽑으면 기간 밖으로 잘못 본다
        JobRunReport report = job.runDue(at(LocalDate.of(2026, 11, 23), 0, 0));

        assertThat(report.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("CREATED"));
        verify(shortfallCheck).shortfalls(USER, LocalDate.of(2026, 11, 23));
    }

    // ───────── 정기 실행: 시각·하루 한 번 ─────────

    @Test
    void 받을_시각_전에는_판정하지_않는다() {
        JobRunReport report = job.runDue(at(TODAY, 8, 29));

        assertThat(report.targets()).isZero();
        verify(shortfallCheck, never()).shortfalls(anyLong(), any());
        verify(marks, never()).markChecked(anyLong(), any());
    }

    @Test
    void 받을_시각이_되면_판정하고_오늘_판정했다고_표시한다() {
        shortfalls(shortfall(1L, false));

        JobRunReport report = job.runDue(at(TODAY, 8, 30));

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.refType()).isEqualTo("USER_CARD");
            assertThat(item.refId()).isEqualTo(1L);
            assertThat(item.userId()).isEqualTo(USER);
            assertThat(item.result()).isEqualTo("CREATED");
            assertThat(item.amount()).isEqualTo(120_000L);
        });
        verify(marks).markChecked(USER, TODAY);
    }

    @Test
    void 시각이_지난_뒤에도_오늘_판정하지_않았으면_따라잡는다() {
        shortfalls();

        assertThat(job.runDue(at(TODAY, 21, 0)).results()).singleElement()
                .satisfies(item -> assertThat(item.result()).isEqualTo("NONE"));
    }

    @Test
    void 오늘_이미_판정했으면_다시_하지_않는다() {
        when(marks.checked(USER, TODAY)).thenReturn(true);

        assertThat(job.runDue(at(TODAY, 9, 0)).targets()).isZero();
        verify(shortfallCheck, never()).shortfalls(anyLong(), any());
    }

    @Test
    void 사용자가_정한_실적_알림_시각을_따르고_잔고_알림_시각은_보지_않는다() {
        when(settings.read(USER)).thenReturn(new NotificationSettings(true, true, true, LocalTime.of(21, 0),
                LocalTime.of(7, 0)));
        shortfalls();

        assertThat(job.runDue(at(TODAY, 20, 59)).targets()).isZero();
        assertThat(job.runDue(at(TODAY, 21, 0)).targets()).isEqualTo(1);
    }

    @Test
    void 실적_알림을_끈_사용자는_대상이_아니다() {
        when(settings.read(USER)).thenReturn(new NotificationSettings(false, true, true,
                NotificationSettings.DEFAULT_TIME, NotificationSettings.DEFAULT_TIME));

        assertThat(job.runDue(at(TODAY, 9, 0)).targets()).isZero();
        verify(shortfallCheck, never()).shortfalls(anyLong(), any());
        verify(marks, never()).markChecked(anyLong(), any());
    }

    @Test
    void 한_사용자가_실패해도_다음_사용자는_판정하고_실패한_사용자도_그날은_다시_부르지_않는다() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(USER, OTHER));
        when(shortfallCheck.shortfalls(USER, TODAY)).thenThrow(new IllegalStateException("카드 상품 없음"));
        when(shortfallCheck.shortfalls(OTHER, TODAY)).thenReturn(List.of());

        JobRunReport report = job.runDue(at(TODAY, 9, 0));

        assertThat(report.results()).extracting(JobRunReport.Item::userId, JobRunReport.Item::result)
                .containsExactly(tuple(USER, "FAILED"), tuple(OTHER, "NONE"));
        assertThat(report.results().get(0).refType()).isEqualTo("APP_USER");
        assertThat(report.results().get(0).message()).isEqualTo("IllegalStateException");
        verify(marks).markChecked(USER, TODAY);
        verify(marks).markChecked(OTHER, TODAY);
    }

    @Test
    void 설정_저장값이_깨진_사용자는_실패로_남기고_그날은_다시_읽지_않는다() {
        when(settings.read(USER)).thenThrow(new IllegalStateException("알림 설정 저장값이 올바르지 않다 (userId=7)"));

        JobRunReport report = job.runDue(at(TODAY, 9, 0));

        assertThat(report.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("FAILED"));
        verify(marks).markChecked(USER, TODAY);
    }

    // ───────── 카드별 알림 ─────────

    @Test
    void 카드마다_카드와_월로_된_키로_한_건씩_쌓고_실적_화면으로_이동한다() {
        shortfalls(shortfall(1L, false), shortfall(2L, false));

        JobRunReport report = job.runDue(at(TODAY, 9, 0));

        ArgumentCaptor<NotificationWriter.Draft> drafts = ArgumentCaptor.forClass(NotificationWriter.Draft.class);
        verify(writer, times(2)).write(drafts.capture());
        assertThat(drafts.getAllValues()).extracting(NotificationWriter.Draft::deduplicationKey)
                .containsExactly("PERFORMANCE_SHORTFALL:1:2026-11", "PERFORMANCE_SHORTFALL:2:2026-11");
        NotificationWriter.Draft first = drafts.getAllValues().get(0);
        assertThat(first.userId()).isEqualTo(USER);
        assertThat(first.type()).isEqualTo(NotificationType.PERFORMANCE_SHORTFALL);
        assertThat(first.targetType()).isEqualTo("USER_CARD");
        assertThat(first.targetId()).isEqualTo(1L);
        assertThat(first.title()).isEqualTo("실적 부족 예상");
        assertThat(first.body()).isEqualTo(
                "카드1 이번 달 실적이 30만원 이상 기준까지 120,000원 남았어요. 이번 달이 5일 남았고 지금 추세면 기준에 못 미칠 수 있어요");
        assertThat(report.results()).extracting(JobRunReport.Item::refId).containsExactly(1L, 2L);
    }

    @Test
    void 이미_쌓은_카드는_DUPLICATE로_남는다() {
        shortfalls(shortfall(1L, false));
        when(writer.write(any())).thenReturn(NotificationWriter.Result.DUPLICATE);

        assertThat(job.runDue(at(TODAY, 9, 0)).results()).singleElement()
                .satisfies(item -> assertThat(item.result()).isEqualTo("DUPLICATE"));
    }

    @Test
    void 인정액_확인_중인_카드는_쌓지_않고_다른_카드는_쌓는다() {
        shortfalls(shortfall(1L, true), shortfall(2L, false));

        JobRunReport report = job.runDue(at(TODAY, 9, 0));

        assertThat(report.results()).extracting(JobRunReport.Item::refId, JobRunReport.Item::result)
                .containsExactly(tuple(1L, "SKIPPED"), tuple(2L, "CREATED"));
        ArgumentCaptor<NotificationWriter.Draft> draft = ArgumentCaptor.forClass(NotificationWriter.Draft.class);
        verify(writer).write(draft.capture());
        assertThat(draft.getValue().targetId()).isEqualTo(2L);
    }

    @Test
    void 말일에는_오늘이_마지막_날이라고_쓴다() {
        NotificationWriter.Draft draft = NotificationMessages.performanceShortfall(USER, 1L,
                YearMonth.of(2026, 11), "생활카드", "30만원 이상", 5_000L, 0);

        assertThat(draft.body()).isEqualTo(
                "생활카드 이번 달 실적이 30만원 이상 기준까지 5,000원 남았어요. 오늘이 이번 달 마지막 날이고 지금 추세면 기준에 못 미칠 수 있어요");
    }

    // ───────── 수동 실행 ─────────

    @Test
    void 수동_실행은_기간_시각_오늘_판정_표시와_상관없이_판정한다() {
        LocalDate early = LocalDate.of(2026, 11, 13);
        job = jobAt(at(early, 3, 0));
        when(marks.checked(anyLong(), any())).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(USER))).thenReturn(List.of(USER));
        when(shortfallCheck.shortfalls(USER, early)).thenReturn(List.of(shortfall(1L, false)));

        JobRunReport report = job.run(new JobRunCommand(at(early, 3, 0), USER, false));

        assertThat(report.results()).singleElement().satisfies(item -> assertThat(item.result()).isEqualTo("CREATED"));
        ArgumentCaptor<NotificationWriter.Draft> draft = ArgumentCaptor.forClass(NotificationWriter.Draft.class);
        verify(writer).write(draft.capture());
        assertThat(draft.getValue().deduplicationKey()).isEqualTo("PERFORMANCE_SHORTFALL:1:2026-11");
        verify(marks, never()).markChecked(anyLong(), any());
    }

    @Test
    void 수동_실행_dryRun은_쌓을_카드만_센다() {
        shortfalls(shortfall(1L, false), shortfall(2L, true));

        JobRunReport report = job.run(new JobRunCommand(at(TODAY, 9, 0), null, true));

        assertThat(report.results()).extracting(JobRunReport.Item::refId, JobRunReport.Item::result)
                .containsExactly(tuple(1L, "DRY_RUN"), tuple(2L, "SKIPPED"));
        verify(writer, never()).write(any());
    }

    @Test
    void 수동_실행에서_알림을_껐으면_건너뛰고_판정하지_않는다() {
        when(settings.read(USER)).thenReturn(new NotificationSettings(false, true, true,
                NotificationSettings.DEFAULT_TIME, NotificationSettings.DEFAULT_TIME));

        assertThat(job.run(new JobRunCommand(at(TODAY, 9, 0), null, false)).results()).singleElement()
                .satisfies(item -> {
                    assertThat(item.refType()).isEqualTo("APP_USER");
                    assertThat(item.result()).isEqualTo("SKIPPED");
                });
        verify(shortfallCheck, never()).shortfalls(anyLong(), any());
    }

    @Test
    void 수동_실행에서_한_사용자가_실패해도_다음_사용자를_판정한다() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(USER, OTHER));
        when(shortfallCheck.shortfalls(USER, TODAY)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(shortfallCheck.shortfalls(OTHER, TODAY)).thenReturn(List.of(shortfall(3L, false)));

        JobRunReport report = job.run(new JobRunCommand(at(TODAY, 9, 0), null, false));

        assertThat(report.results()).extracting(JobRunReport.Item::userId, JobRunReport.Item::result)
                .containsExactly(tuple(USER, "FAILED"), tuple(OTHER, "CREATED"));
        assertThat(report.results().get(0).message()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void 정기_실행은_jobs_enabled가_꺼져_있으면_돌지_않는다() {
        JobLocks locks = mock(JobLocks.class);
        PerformanceShortfallNotifyJob off = new PerformanceShortfallNotifyJob(jdbc, settings, writer, shortfallCheck,
                marks, locks, new NotificationProperties("0 * * * * *", "0 * * * * *", false),
                Clock.fixed(at(TODAY, 9, 0), ZoneOffset.UTC));

        off.scheduled();

        verifyNoInteractions(locks, jdbc);
    }

    private PerformanceShortfallNotifyJob jobAt(Instant now) {
        return new PerformanceShortfallNotifyJob(jdbc, settings, writer, shortfallCheck, marks, mock(JobLocks.class),
                new NotificationProperties("0 * * * * *", "0 * * * * *", true), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void shortfalls(Shortfall... found) {
        when(shortfallCheck.shortfalls(eq(USER), any())).thenReturn(List.of(found));
    }

    private static Shortfall shortfall(long userCardId, boolean provisional) {
        return new Shortfall(userCardId, "카드" + userCardId, 300_000L, "30만원 이상", 180_000L, 120_000L, 5,
                provisional);
    }

    private static NotificationSettings settingsAt(LocalTime time) {
        return new NotificationSettings(true, true, true, time, NotificationSettings.DEFAULT_TIME);
    }

    /** 그날 한국 시각. */
    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(KST).toInstant();
    }
}
