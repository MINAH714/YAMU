package com.yamu.be.user;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴 뒤 보관 기간(기본 30일)이 지난 사용자의 재가입 열쇠를 지운다(S15P31E103-380, 운영 점검 Q-6 정책 · 임시).
 *
 * <p>탈퇴({@link WithdrawalService})는 재가입 때 이어받도록 phone_hash·birth_date·이름 해시·SSAFY 회원 ID·userKey와 카드 행의
 * 카드번호·카드사 계좌 해시를 남긴다. 기간이 지나면 이 작업이 지우고, 그 뒤 같은 사람은 재가입이 아닌 새 회원으로 가입한다.
 * SSAFY 계정은 지우는 API가 없어 SSAFY 쪽에 남는다.
 *
 * <ul>
 *   <li>사용자 행을 잠근 뒤 다시 확인하고 지운다. 같은 사람의 재가입({@link RejoinService})도 옛 행을 잠그므로 둘은 줄을 선다.
 *       재가입이 먼저면 열쇠가 이미 옮겨져 이 작업은 카드사 계좌 해시만 지우고, 이 작업이 먼저면 재가입은 옛 행을 찾지 못해 새 회원이 된다</li>
 *   <li>행은 지우지 않는다. 거래·청구·감사 로그가 가리키기 때문이다(FK RESTRICT). 지운 뒤에는 행에 사람과 이어지는 값이 남지 않는다</li>
 *   <li>카드번호는 NOT NULL·UNIQUE(user_id, card_no_hash)라 비우지 않고 무작위 값으로 덮는다(재가입과 같은 방식). 끝 4자리는 표시용이라 둔다</li>
 *   <li>이미 지운 행은 대상 조건에서 빠지므로 여러 번 돌려도 같다(멱등)</li>
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(WithdrawnKeyPurgeProperties.class)
public class WithdrawnKeyPurgeJob implements ManualJob {

    public static final String NAME = "withdrawn-key-purge";
    private static final String REF_TYPE = "APP_USER";
    private static final int PAGE_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppUserRepository userRepository;
    private final PersonalDataCipher cipher;
    private final TransactionTemplate transaction;
    private final JobLocks jobLocks;
    private final WithdrawnKeyPurgeProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WithdrawnKeyPurgeJob(NamedParameterJdbcTemplate jdbc, AppUserRepository userRepository,
            PersonalDataCipher cipher, TransactionTemplate transaction, JobLocks jobLocks,
            WithdrawnKeyPurgeProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.cipher = cipher;
        this.transaction = transaction;
        this.jobLocks = jobLocks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Scheduled(cron = "${yamu.user.withdrawn-key-purge.cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            JobRunReport report = jobLocks.locked(NAME, () -> run(JobRunCommand.now(clock)));
            log.info("탈퇴 재가입 열쇠 정리 끝 targets={} succeeded={} failed={}", report.targets(),
                    report.count(JobRunReport.Outcome.SUCCEEDED), report.count(JobRunReport.Outcome.FAILED));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.JOB_ALREADY_RUNNING) {
                throw e;
            }
            log.info("탈퇴 재가입 열쇠 정리가 이미 돌고 있어 이번 회차는 건너뜀");
        }
    }

    /** 잠금 없이 한 번 돈다. 잠금은 부르는 쪽(스케줄·수동 실행)이 잡는다. */
    @Override
    public JobRunReport run(JobRunCommand command) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(command.asOf(), ZoneOffset.UTC).minus(properties.retention());
        List<JobRunReport.Item> results = new ArrayList<>();
        long lastId = 0;
        while (true) {
            List<Long> page = targets(cutoff, command.userId(), lastId);
            for (Long userId : page) {
                lastId = userId;
                results.add(command.dryRun()
                        ? JobRunReport.Item.dryRun(REF_TYPE, userId, userId, null)
                        : purgeOne(userId, cutoff));
            }
            if (page.size() < PAGE_SIZE) {
                return new JobRunReport(results);
            }
        }
    }

    /** 탈퇴한 지 cutoff보다 오래됐고 열쇠(또는 카드사 계좌 해시)가 남은 사용자 */
    private List<Long> targets(LocalDateTime cutoff, Long userId, long lastId) {
        return jdbc.queryForList("""
                SELECT u.user_id FROM app_user u
                 WHERE u.status = 'WITHDRAWN' AND u.deleted_at <= :cutoff AND u.user_id > :lastId
                   AND (:userId IS NULL OR u.user_id = :userId)
                   AND (u.phone_hash IS NOT NULL OR u.birth_date IS NOT NULL OR u.withdrawn_name_hash IS NOT NULL
                        OR u.ssafy_member_id IS NOT NULL OR u.ssafy_user_key IS NOT NULL
                        OR EXISTS (SELECT 1 FROM user_card c
                                    WHERE c.user_id = u.user_id AND c.issuer_account_hash IS NOT NULL))
                 ORDER BY u.user_id
                 LIMIT :limit
                """, new MapSqlParameterSource("cutoff", Timestamp.valueOf(cutoff)).addValue("lastId", lastId)
                .addValue("userId", userId).addValue("limit", PAGE_SIZE), Long.class);
    }

    private JobRunReport.Item purgeOne(Long userId, LocalDateTime cutoff) {
        try {
            Boolean purged = transaction.execute(status -> {
                AppUser user = userRepository.findByIdForUpdate(userId).orElse(null);
                // 고른 뒤 잠그기 전에 상태가 바뀌었을 수 있다(탈퇴 시각은 바뀌지 않지만 같은 기준으로 다시 본다).
                if (user == null || !user.isWithdrawn() || user.getDeletedAt() == null
                        || user.getDeletedAt().isAfter(cutoff)) {
                    return false;
                }
                user.releaseRejoinKeys();
                userRepository.flush();
                wipeCards(userId);
                return true;
            });
            return Boolean.TRUE.equals(purged)
                    ? JobRunReport.Item.succeeded(REF_TYPE, userId, userId, "PURGED", null, null)
                    : JobRunReport.Item.skipped(REF_TYPE, userId, userId, "대상이 아닙니다(잠근 뒤 다시 확인)");
        } catch (RuntimeException e) {
            log.error("탈퇴 재가입 열쇠 정리 실패, 운영 확인 userId={}", userId, e);
            return JobRunReport.Item.failed(REF_TYPE, userId, userId, "재가입 열쇠를 지우지 못했습니다(서버 로그 확인)");
        }
    }

    /** 카드번호는 무작위로 덮고(NOT NULL·UNIQUE), 카드사 계좌 세 칸은 함께 비운다(CHECK: 모두 있거나 모두 없음). */
    private void wipeCards(Long userId) {
        Timestamp now = Timestamp.from(clock.instant());
        List<Long> cardIds = jdbc.queryForList("SELECT user_card_id FROM user_card WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), Long.class);
        for (Long cardId : cardIds) {
            jdbc.update("""
                    UPDATE user_card
                       SET card_no_enc = :enc, card_no_hash = :hash,
                           issuer_account_bank_code = NULL, issuer_account_hash = NULL, issuer_account_last4 = NULL,
                           updated_at = :now, version = version + 1
                     WHERE user_card_id = :id
                    """, new MapSqlParameterSource("id", cardId)
                    .addValue("enc", cipher.encrypt(dummy(), CipherField.USER_CARD_NO))
                    .addValue("hash", randomBytes(32)).addValue("now", now));
        }
    }

    private String dummy() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24));
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }
}
