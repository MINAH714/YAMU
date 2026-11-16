package com.yamu.be.notification;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import com.yamu.be.performance.PerformanceShortfallCheck;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실적 부족 알림 작업(Task 9.3.3, S15P31E103-465, {@code performance-shortfall-notify}). 사용자가 정한 실적 알림 시각
 * ({@code performanceTime}, 기본 08:30 한국 시각)에 이번 달 첫 구간을 못 채울 것 같은 카드({@link PerformanceShortfallCheck})를 찾고,
 * 카드마다 알림을 하나 쌓는다. 판정 규칙은 그 클래스 설명을 본다.
 *
 * <ul>
 *   <li>기간(2026-11-13 결정): 이번 달이 {@value #WINDOW_DAYS}일 이하로 남았을 때만 판정한다(FR-030 "월말에 가까워졌을 때"). 30일인 달은
 *       23일, 31일인 달은 24일부터다. 그 전의 추세는 결제가 적어 흔들린다.</li>
 *   <li>반복: 같은 카드·같은 달에 한 번. 중복 방지 키는 카드+월이다. 부족이 풀렸다가 다시 생겨도 그달은 다시 알리지 않는다.</li>
 *   <li>시각: 매분 깨어, 받을 시각이 지났고 오늘 아직 판정하지 않은 사용자만 판정한다({@link PerformanceShortfallMarks}). 하루 한 번이다.</li>
 *   <li>인정액 확인 중: 인정액이 정해지지 않은 결제가 있는 카드는 실적이 낮게 잡혔을 수 있어 그날은 쌓지 않는다. 키를 쓰지 않으므로 다음 날 다시
 *       판정한다.</li>
 *   <li>알림 멈춤: 실적 판단(S15P31E103-375)에서 그달 이 카드의 실적 부족 알림을 멈췄으면 쌓지 않고 SKIPPED로 남긴다. 판단을 되돌리면
 *       다음 판정부터 다시 쌓을 수 있다.</li>
 *   <li>실패 격리: 한 사용자의 실패는 그 사용자만 FAILED로 남기고 다음으로 간다. 그날은 다시 시도하지 않는다.</li>
 *   <li>수동 실행: 시각·"오늘 판정함"·기간과 상관없이 지금 판정한다(시연용). {@code userId}가 있으면 그 사용자만, {@code dryRun}이면 쌓을
 *       카드만 센다. {@code asOf}는 쓰지 않는다. 실적 추세가 실제 오늘 날짜로 계산되기 때문이다.</li>
 * </ul>
 *
 * <p>스케줄은 {@code yamu.notification.jobs-enabled=true}일 때만 돈다(기본 끔).
 */
@Slf4j
@Component
public class PerformanceShortfallNotifyJob implements ManualJob {

    public static final String NAME = "performance-shortfall-notify";
    /** 이번 달이 이 날 수 이하로 남았을 때만 정기 판정한다(오늘 뺌, 말일이면 0). */
    static final int WINDOW_DAYS = 7;
    static final String USER_REF = "APP_USER";
    static final String CARD_REF = "USER_CARD";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final NotificationSettingsStore settings;
    private final NotificationWriter writer;
    private final PerformanceShortfallCheck shortfallCheck;
    private final PerformanceShortfallMarks marks;
    private final JobLocks jobLocks;
    private final NotificationProperties properties;
    private final Clock clock;

    PerformanceShortfallNotifyJob(JdbcTemplate jdbc, NotificationSettingsStore settings, NotificationWriter writer,
            PerformanceShortfallCheck shortfallCheck, PerformanceShortfallMarks marks, JobLocks jobLocks,
            NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.writer = writer;
        this.shortfallCheck = shortfallCheck;
        this.marks = marks;
        this.jobLocks = jobLocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${yamu.notification.performance-shortfall-cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            JobRunReport report = jobLocks.locked(NAME, () -> runDue(clock.instant()));
            if (report.targets() > 0) {
                log.info("실적 부족 알림 작업 끝 targets={} succeeded={} failed={} skipped={}", report.targets(),
                        report.count(JobRunReport.Outcome.SUCCEEDED), report.count(JobRunReport.Outcome.FAILED),
                        report.count(JobRunReport.Outcome.SKIPPED));
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.JOB_ALREADY_RUNNING) {
                throw e;
            }
            log.debug("실적 부족 알림 작업이 아직 돌고 있어 이번 분은 건너뜀");
        }
    }

    /** 정기 실행: 기간 안이고, 받을 시각이 지났고, 오늘 아직 판정하지 않은 사용자. 시각이 안 된 사용자·꺼 둔 사용자는 결과에 넣지 않는다. */
    JobRunReport runDue(Instant now) {
        ZonedDateTime kst = now.atZone(KST);
        LocalDate today = kst.toLocalDate();
        if (!inWindow(today)) {
            return new JobRunReport(List.of());
        }
        LocalTime time = kst.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        List<JobRunReport.Item> results = new ArrayList<>();
        for (long userId : activeUsers(null)) {
            boolean marked = false;
            try {
                NotificationSettings userSettings = settings.read(userId);
                if (!userSettings.performanceEnabled() || userSettings.performanceTime().isAfter(time)
                        || marks.checked(userId, today)) {
                    continue;
                }
                // 판정 전에 표시한다. 실패해도 그날은 다시 부르지 않는다(위 클래스 설명)
                marks.markChecked(userId, today);
                marked = true;
                results.addAll(check(userId, today, false));
            } catch (RuntimeException e) {
                results.add(failed(userId, e));
                if (!marked) {
                    markCheckedQuietly(userId, today);
                }
            }
        }
        return new JobRunReport(results);
    }

    /** 이번 달 남은 날(오늘 뺌)이 {@value #WINDOW_DAYS}일 이하인지. */
    static boolean inWindow(LocalDate today) {
        return today.lengthOfMonth() - today.getDayOfMonth() <= WINDOW_DAYS;
    }

    /** 설정 저장값이 깨진 사용자 등이 매분 실패 로그를 남기지 않게 그날은 판정한 것으로 둔다. */
    private void markCheckedQuietly(long userId, LocalDate today) {
        try {
            marks.markChecked(userId, today);
        } catch (RuntimeException e) {
            log.warn("실적 부족 판정 표시 실패 userId={}", userId, e);
        }
    }

    /** 수동 실행: 시각·오늘 판정 표시·기간과 상관없이 지금 판정한다. */
    @Override
    public JobRunReport run(JobRunCommand command) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        List<JobRunReport.Item> results = new ArrayList<>();
        for (long userId : activeUsers(command.userId())) {
            try {
                results.addAll(check(userId, today, command.dryRun()));
            } catch (RuntimeException e) {
                results.add(failed(userId, e));
            }
        }
        return new JobRunReport(results);
    }

    /** 한 사용자의 판정. 쌓을 카드가 있으면 카드마다 한 줄, 없으면 사용자 한 줄이다. */
    private List<JobRunReport.Item> check(long userId, LocalDate today, boolean dryRun) {
        if (!settings.read(userId).performanceEnabled()) {
            return List.of(JobRunReport.Item.skipped(USER_REF, userId, userId, "실적 알림을 껐습니다"));
        }
        List<PerformanceShortfallCheck.Shortfall> found = shortfallCheck.shortfalls(userId, today);
        if (found.isEmpty()) {
            return List.of(JobRunReport.Item.succeeded(USER_REF, userId, userId, "NONE", null, null));
        }
        YearMonth month = YearMonth.from(today);
        List<JobRunReport.Item> items = new ArrayList<>();
        for (PerformanceShortfallCheck.Shortfall shortfall : found) {
            long card = shortfall.userCardId();
            if (shortfall.muted()) {
                items.add(JobRunReport.Item.skipped(CARD_REF, card, userId,
                        "실적 판단에서 이번 달 이 카드의 실적 부족 알림을 멈췄습니다"));
            } else if (shortfall.provisional()) {
                items.add(JobRunReport.Item.skipped(CARD_REF, card, userId,
                        "실적 인정액을 확인 중인 결제가 있어 다음 판정으로 미룹니다"));
            } else if (dryRun) {
                items.add(JobRunReport.Item.dryRun(CARD_REF, card, userId, shortfall.remaining()));
            } else {
                NotificationWriter.Result result = writer.write(NotificationMessages.performanceShortfall(userId,
                        card, month, shortfall.cardName(), shortfall.targetLabel(), shortfall.remaining(),
                        shortfall.daysLeft()));
                log.info("실적 부족 판정 userId={} userCardId={} month={} remaining={} result={}", userId, card, month,
                        shortfall.remaining(), result);
                items.add(JobRunReport.Item.succeeded(CARD_REF, card, userId, result.name(), shortfall.remaining(),
                        null));
            }
        }
        return items;
    }

    /** 가입을 마친(ACTIVE) 사용자 번호 순. userId가 있으면 그 사용자만(가입 전·탈퇴면 빈 목록). */
    private List<Long> activeUsers(Long userId) {
        if (userId != null) {
            return jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id = ? AND status = 'ACTIVE'",
                    Long.class, userId);
        }
        return jdbc.queryForList("SELECT user_id FROM app_user WHERE status = 'ACTIVE' ORDER BY user_id", Long.class);
    }

    private static JobRunReport.Item failed(long userId, RuntimeException e) {
        log.warn("실적 부족 판정 실패 userId={}", userId, e);
        String message = e instanceof BusinessException business ? business.getErrorCode().name()
                : e.getClass().getSimpleName();
        return JobRunReport.Item.failed(USER_REF, userId, userId, message);
    }
}
