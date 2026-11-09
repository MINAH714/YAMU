package com.yamu.be.notification;

import com.yamu.be.forecast.BalanceRiskCheck;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 잔고 위험 알림 작업(Task 9.3.2, {@code balance-risk-notify}). 사용자가 정한 잔고 알림 시각({@code balanceTime}, 기본 08:30 한국 시각)에
 * 잔고예보와 같은 계산으로 통합·계좌별 잔액의 가장 이른 위험 구간을 찾고, 있으면 알림을 하나 쌓는다.
 *
 * <ul>
 *   <li>시각: 매분 깨어, 받을 시각이 지났고 오늘 아직 판정하지 않은 사용자만 판정한다({@link BalanceRiskMarks}). 하루 한 번이다.</li>
 *   <li>반복(2026-11-10 결정): 위험 구간마다 한 번. 중복 방지 키는 구간 첫날이다. 오늘부터 이미 위험인 구간은 어제도 위험이었으면 이어지는 것으로 본다.
 *       예보가 바뀌어 첫날이 달라지면 새 구간으로 보고 다시 알린다.</li>
 *   <li>계좌(임시): 연결된 계좌 전체(기준 계좌 먼저, 최대 {@value BalanceRiskCheck#MAX_ACCOUNTS}개). 앱이 고른 계좌는 서버에 없다.</li>
 *   <li>실패 격리: 한 사용자의 실패(금융망 잔액 조회 실패 등)는 그 사용자만 FAILED로 남기고 다음으로 간다. 그날은 다시 시도하지 않는다
 *       (실패한 금융망을 매분 다시 부르지 않게).</li>
 *   <li>수동 실행: 시각·"오늘 판정함"과 상관없이 지금 판정한다(시연용). {@code userId}가 있으면 그 사용자만, {@code dryRun}이면 대상만 센다.
 *       {@code asOf}는 쓰지 않는다. 잔고예보가 늘 실제 오늘부터 계산하기 때문이다.</li>
 * </ul>
 *
 * <p>스케줄은 {@code yamu.notification.jobs-enabled=true}일 때만 돈다(기본 끔). 한계: 판정마다 연결 계좌 수만큼 금융망 잔액 조회(10분 캐시)와
 * 생활비 추정이 나가므로, 같은 시각을 고른 사용자가 많으면 그 분의 실행이 길어진다. 시연 규모(수십 명)를 전제로 한다.
 */
@Slf4j
@Component
public class BalanceRiskNotifyJob implements ManualJob {

    public static final String NAME = "balance-risk-notify";
    static final String REF_TYPE = "APP_USER";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final NotificationSettingsStore settings;
    private final NotificationContentReader contents;
    private final NotificationWriter writer;
    private final BalanceRiskCheck riskCheck;
    private final BalanceRiskMarks marks;
    private final JobLocks jobLocks;
    private final NotificationProperties properties;
    private final Clock clock;

    BalanceRiskNotifyJob(JdbcTemplate jdbc, NotificationSettingsStore settings, NotificationContentReader contents,
            NotificationWriter writer, BalanceRiskCheck riskCheck, BalanceRiskMarks marks, JobLocks jobLocks,
            NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.contents = contents;
        this.writer = writer;
        this.riskCheck = riskCheck;
        this.marks = marks;
        this.jobLocks = jobLocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${yamu.notification.balance-risk-cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!properties.jobsEnabled()) {
            return;
        }
        try {
            JobRunReport report = jobLocks.locked(NAME, () -> runDue(clock.instant()));
            if (report.targets() > 0) {
                log.info("잔고 위험 알림 작업 끝 targets={} succeeded={} failed={} skipped={}", report.targets(),
                        report.count(JobRunReport.Outcome.SUCCEEDED), report.count(JobRunReport.Outcome.FAILED),
                        report.count(JobRunReport.Outcome.SKIPPED));
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.JOB_ALREADY_RUNNING) {
                throw e;
            }
            log.debug("잔고 위험 알림 작업이 아직 돌고 있어 이번 분은 건너뜀");
        }
    }

    /** 정기 실행: 받을 시각이 지났고 오늘 아직 판정하지 않은 사용자. 시각이 안 된 사용자·꺼 둔 사용자는 결과에 넣지 않는다. */
    JobRunReport runDue(Instant now) {
        ZonedDateTime kst = now.atZone(KST);
        LocalDate today = kst.toLocalDate();
        LocalTime time = kst.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        List<JobRunReport.Item> results = new ArrayList<>();
        for (long userId : activeUsers(null)) {
            boolean marked = false;
            try {
                NotificationSettings userSettings = settings.read(userId);
                if (!userSettings.balanceRiskEnabled() || userSettings.balanceTime().isAfter(time)
                        || marks.checked(userId, today)) {
                    continue;
                }
                // 판정 전에 표시한다. 실패해도 그날은 다시 부르지 않는다(위 클래스 설명)
                marks.markChecked(userId, today);
                marked = true;
                results.add(check(userId, false));
            } catch (RuntimeException e) {
                results.add(failed(userId, e));
                if (!marked) {
                    markCheckedQuietly(userId, today);
                }
            }
        }
        return new JobRunReport(results);
    }

    /** 설정 저장값이 깨진 사용자 등이 매분 실패 로그를 남기지 않게 그날은 판정한 것으로 둔다. */
    private void markCheckedQuietly(long userId, LocalDate today) {
        try {
            marks.markChecked(userId, today);
        } catch (RuntimeException e) {
            log.warn("잔고 위험 판정 표시 실패 userId={}", userId, e);
        }
    }

    /** 수동 실행: 시각·오늘 판정 표시와 상관없이 지금 판정한다. */
    @Override
    public JobRunReport run(JobRunCommand command) {
        List<JobRunReport.Item> results = new ArrayList<>();
        for (long userId : activeUsers(command.userId())) {
            try {
                results.add(check(userId, command.dryRun()));
            } catch (RuntimeException e) {
                results.add(failed(userId, e));
            }
        }
        return new JobRunReport(results);
    }

    private JobRunReport.Item check(long userId, boolean dryRun) {
        if (!settings.read(userId).balanceRiskEnabled()) {
            return JobRunReport.Item.skipped(REF_TYPE, userId, userId, "잔고 위험 알림을 껐습니다");
        }
        List<Long> accounts = contents.connectedAccounts(userId, BalanceRiskCheck.MAX_ACCOUNTS);
        if (accounts.isEmpty()) {
            return JobRunReport.Item.skipped(REF_TYPE, userId, userId, "연결된 계좌가 없습니다");
        }
        if (dryRun) {
            return JobRunReport.Item.dryRun(REF_TYPE, userId, userId, null);
        }
        Optional<BalanceRiskCheck.Risk> found = riskCheck.firstRisk(userId, accounts);
        if (found.isEmpty()) {
            marks.clearRisk(userId);
            return JobRunReport.Item.succeeded(REF_TYPE, userId, userId, "SAFE", null, null);
        }
        BalanceRiskCheck.Risk risk = found.get();
        boolean continuing = risk.from().equals(risk.today())
                && marks.riskSeenSince(userId, risk.today().minusDays(1));
        marks.rememberRisk(userId, risk.today());
        if (continuing) {
            return JobRunReport.Item.succeeded(REF_TYPE, userId, userId, "CONTINUING", risk.shortfall(), null);
        }
        boolean accountRisk = risk.accountId() != null;
        NotificationWriter.Result result = writer.write(NotificationMessages.balanceRisk(userId,
                accountRisk ? risk.accountId() : accounts.get(0), accountRisk, risk.from(), risk.shortfall(),
                risk.threshold(), risk.today()));
        log.info("잔고 위험 판정 userId={} from={} level={} accountId={} result={}", userId, risk.from(), risk.level(),
                risk.accountId(), result);
        return JobRunReport.Item.succeeded(REF_TYPE, userId, userId, result.name(), risk.shortfall(), null);
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
        log.warn("잔고 위험 판정 실패 userId={}", userId, e);
        String message = e instanceof BusinessException business ? business.getErrorCode().name()
                : e.getClass().getSimpleName();
        return JobRunReport.Item.failed(REF_TYPE, userId, userId, message);
    }
}
