package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.forecast.BalanceRiskCheck;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 잔고 위험 알림 작업(Task 9.3.2)의 대상 고르기·반복 규칙·실패 격리. 잔고예보 계산·DB·Redis는 대역이다. */
class BalanceRiskNotifyJobTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final long USER = 7L;
    static final long OTHER = 8L;
    static final long ACCOUNT = 31L;

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NotificationSettingsStore settings = mock(NotificationSettingsStore.class);
    NotificationContentReader contents = mock(NotificationContentReader.class);
    NotificationWriter writer = mock(NotificationWriter.class);
    BalanceRiskCheck riskCheck = mock(BalanceRiskCheck.class);
    BalanceRiskMarks marks = mock(BalanceRiskMarks.class);

    BalanceRiskNotifyJob job;

    @BeforeEach
    void setUp() {
        job = new BalanceRiskNotifyJob(jdbc, settings, contents, writer, riskCheck, marks, mock(JobLocks.class),
                new NotificationProperties("0 * * * * *", true), Clock.fixed(at(8, 30), ZoneOffset.UTC));
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(USER));
        when(settings.read(anyLong())).thenReturn(NotificationSettings.DEFAULTS);
        when(contents.connectedAccounts(anyLong(), eq(BalanceRiskCheck.MAX_ACCOUNTS))).thenReturn(List.of(ACCOUNT));
        when(writer.write(any())).thenReturn(NotificationWriter.Result.CREATED);
    }

    // ───────── 정기 실행: 시각·하루 한 번 ─────────

    @Test
    void 받을_시각_전에는_판정하지_않는다() {
        JobRunReport report = job.runDue(at(8, 29));

        assertThat(report.targets()).isZero();
        verify(riskCheck, never()).firstRisk(anyLong(), any());
        verify(marks, never()).markChecked(anyLong(), any());
    }

    @Test
    void 받을_시각이_되면_판정하고_오늘_판정했다고_표시한다() {
        risk(TODAY.plusDays(5));

        JobRunReport report = job.runDue(at(8, 30));

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(USER);
            assertThat(item.result()).isEqualTo("CREATED");
        });
        verify(marks).markChecked(USER, TODAY);
        ArgumentCaptor<NotificationWriter.Draft> draft = ArgumentCaptor.forClass(NotificationWriter.Draft.class);
        verify(writer).write(draft.capture());
        assertThat(draft.getValue().type()).isEqualTo(NotificationType.BALANCE_RISK);
        assertThat(draft.getValue().deduplicationKey()).isEqualTo("BALANCE_RISK:2026-11-15");
        assertThat(draft.getValue().targetType()).isEqualTo("BALANCE");
        assertThat(draft.getValue().targetId()).isEqualTo(ACCOUNT);
        assertThat(draft.getValue().body())
                .isEqualTo("11월 15일부터 예상 잔액이 안전 잔액 100,000원보다 최대 42,000원 부족해요. 잔고예보에서 원인을 확인해 보세요");
    }

    @Test
    void 시각이_지난_뒤에도_오늘_판정하지_않았으면_따라잡는다() {
        risk(TODAY.plusDays(5));

        assertThat(job.runDue(at(11, 5)).targets()).isEqualTo(1);
    }

    @Test
    void 오늘_이미_판정했으면_다시_하지_않는다() {
        when(marks.checked(USER, TODAY)).thenReturn(true);

        assertThat(job.runDue(at(9, 0)).targets()).isZero();
        verify(riskCheck, never()).firstRisk(anyLong(), any());
    }

    @Test
    void 사용자가_정한_시각을_따른다() {
        when(settings.read(USER)).thenReturn(new NotificationSettings(true, true, true, LocalTime.of(8, 30),
                LocalTime.of(21, 0)));

        assertThat(job.runDue(at(20, 59)).targets()).isZero();
        risk(TODAY.plusDays(2));
        assertThat(job.runDue(at(21, 0)).targets()).isEqualTo(1);
    }

    @Test
    void 잔고_위험_알림을_끈_사용자는_대상이_아니다() {
        when(settings.read(USER)).thenReturn(new NotificationSettings(true, false, true, LocalTime.of(8, 30),
                LocalTime.of(8, 30)));

        assertThat(job.runDue(at(9, 0)).targets()).isZero();
        verify(riskCheck, never()).firstRisk(anyLong(), any());
    }

    @Test
    void 한_사용자가_실패해도_다음_사용자는_판정하고_실패한_사용자도_그날은_다시_부르지_않는다() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(USER, OTHER));
        when(riskCheck.firstRisk(eq(USER), any())).thenThrow(new BusinessException(ErrorCode.FIN_API_UNAVAILABLE));
        when(riskCheck.firstRisk(eq(OTHER), any())).thenReturn(Optional.empty());

        JobRunReport report = job.runDue(at(9, 0));

        assertThat(report.results()).extracting(JobRunReport.Item::userId, JobRunReport.Item::result)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(USER, "FAILED"),
                        org.assertj.core.groups.Tuple.tuple(OTHER, "SAFE"));
        assertThat(report.results().get(0).message()).isEqualTo("FIN_API_UNAVAILABLE");
        verify(marks).markChecked(USER, TODAY);
        verify(marks).markChecked(OTHER, TODAY);
    }

    @Test
    void 설정_저장값이_깨진_사용자는_실패로_남기고_그날은_다시_읽지_않는다() {
        when(settings.read(USER)).thenThrow(new IllegalStateException("알림 설정 저장값이 올바르지 않다 (userId=7)"));

        JobRunReport report = job.runDue(at(9, 0));

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.result()).isEqualTo("FAILED");
            assertThat(item.message()).isEqualTo("IllegalStateException");
        });
        verify(marks).markChecked(USER, TODAY);
    }

    // ───────── 반복 규칙 ─────────

    @Test
    void 위험이_없으면_알리지_않고_이어지는_위험_표시를_지운다() {
        when(riskCheck.firstRisk(eq(USER), any())).thenReturn(Optional.empty());

        JobRunReport report = job.run(new JobRunCommand(at(9, 0), null, false));

        assertThat(report.results()).singleElement().extracting(JobRunReport.Item::result).isEqualTo("SAFE");
        verify(writer, never()).write(any());
        verify(marks).clearRisk(USER);
    }

    @Test
    void 오늘부터_위험이고_어제도_위험이었으면_이어지는_구간이라_다시_알리지_않는다() {
        risk(TODAY);
        when(marks.riskSeenSince(USER, TODAY.minusDays(1))).thenReturn(true);

        JobRunReport report = job.run(new JobRunCommand(at(9, 0), null, false));

        assertThat(report.results()).singleElement().extracting(JobRunReport.Item::result).isEqualTo("CONTINUING");
        verify(writer, never()).write(any());
        verify(marks).rememberRisk(USER, TODAY);
    }

    @Test
    void 오늘부터_위험인데_어제는_위험이_아니었으면_알린다() {
        risk(TODAY);

        job.run(new JobRunCommand(at(9, 0), null, false));

        ArgumentCaptor<NotificationWriter.Draft> draft = ArgumentCaptor.forClass(NotificationWriter.Draft.class);
        verify(writer).write(draft.capture());
        assertThat(draft.getValue().deduplicationKey()).isEqualTo("BALANCE_RISK:2026-11-10");
        assertThat(draft.getValue().body()).startsWith("지금 예상 잔액이");
        verify(marks).rememberRisk(USER, TODAY);
    }

    @Test
    void 앞으로의_구간은_어제_위험이었어도_첫날_키로_쌓는다() {
        // 구간 첫날이 미래면 키가 같으면 작성기가 중복으로 거른다. 여기서는 작성기에 넘기기만 확인한다
        risk(TODAY.plusDays(3));
        when(marks.riskSeenSince(USER, TODAY.minusDays(1))).thenReturn(true);
        when(writer.write(any())).thenReturn(NotificationWriter.Result.DUPLICATE);

        JobRunReport report = job.run(new JobRunCommand(at(9, 0), null, false));

        assertThat(report.results()).singleElement().extracting(JobRunReport.Item::result).isEqualTo("DUPLICATE");
    }

    @Test
    void 계좌_하나의_위험이면_그_계좌로_이동하고_문구에_적는다() {
        long other = 32L;
        when(contents.connectedAccounts(USER, BalanceRiskCheck.MAX_ACCOUNTS)).thenReturn(List.of(ACCOUNT, other));
        when(riskCheck.firstRisk(USER, List.of(ACCOUNT, other))).thenReturn(Optional.of(new BalanceRiskCheck.Risk(
                TODAY, other, TODAY.plusDays(4), TODAY.plusDays(4), "DANGER", -100_000L, 200_000L, 100_000L)));

        job.run(new JobRunCommand(at(9, 0), null, false));

        ArgumentCaptor<NotificationWriter.Draft> draft = ArgumentCaptor.forClass(NotificationWriter.Draft.class);
        verify(writer).write(draft.capture());
        assertThat(draft.getValue().targetId()).isEqualTo(other);
        assertThat(draft.getValue().body()).isEqualTo("11월 14일부터 연결 계좌 한 곳의 예상 잔액이 안전 잔액 100,000원보다 최대 "
                + "200,000원 부족해요. 잔고예보에서 원인을 확인해 보세요");
    }

    // ───────── 수동 실행 ─────────

    @Test
    void 수동_실행은_시각과_상관없이_판정한다() {
        risk(TODAY.plusDays(1));
        when(marks.checked(USER, TODAY)).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(USER))).thenReturn(List.of(USER));

        JobRunReport report = job.run(new JobRunCommand(at(3, 0), USER, false));

        assertThat(report.results()).singleElement().extracting(JobRunReport.Item::result).isEqualTo("CREATED");
        verify(marks, never()).markChecked(anyLong(), any());
    }

    @Test
    void 수동_실행_dryRun은_대상만_센다() {
        JobRunReport report = job.run(new JobRunCommand(at(9, 0), null, true));

        assertThat(report.results()).singleElement().extracting(JobRunReport.Item::result).isEqualTo("DRY_RUN");
        verify(riskCheck, never()).firstRisk(anyLong(), any());
    }

    @Test
    void 연결_계좌가_없거나_알림을_껐으면_건너뛴다() {
        when(contents.connectedAccounts(USER, BalanceRiskCheck.MAX_ACCOUNTS)).thenReturn(List.of());

        assertThat(job.run(new JobRunCommand(at(9, 0), null, false)).results()).singleElement()
                .extracting(JobRunReport.Item::message).isEqualTo("연결된 계좌가 없습니다");

        when(settings.read(USER)).thenReturn(new NotificationSettings(true, false, true, LocalTime.of(8, 30),
                LocalTime.of(8, 30)));
        assertThat(job.run(new JobRunCommand(at(9, 0), null, false)).results()).singleElement()
                .extracting(JobRunReport.Item::message).isEqualTo("잔고 위험 알림을 껐습니다");
        verify(riskCheck, never()).firstRisk(anyLong(), any());
    }

    private void risk(LocalDate from) {
        when(riskCheck.firstRisk(eq(USER), eq(List.of(ACCOUNT)))).thenReturn(Optional.of(new BalanceRiskCheck.Risk(
                TODAY, null, from, from.plusDays(2), "WARNING", 58_000L, 42_000L, 100_000L)));
    }

    /** 오늘(한국) 시각. */
    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(KST).toInstant();
    }
}
