package com.yamu.be.notification;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 한 건을 {@code notification} 표에 쌓는다(Task 9.3.2, S15P31E103-377).
 *
 * <ul>
 *   <li>설정: 사용자 알림 설정의 묶음(실적·잔고 위험·거래)이 꺼져 있으면 쌓지 않는다. 반복 지출 후보·혜택 변경·보안은 설정과 상관없다
 *       (DB 문서 implementation.md). 설정은 쌓는 순간에 읽는다.</li>
 *   <li>중복: {@code uk_notification_dedup(user_id, deduplication_key)}가 같은 사건을 한 번만 받는다. 재시도·두 서버가 같은 사건을
 *       동시에 처리해도 뒤의 INSERT는 중복 키로 실패하고 {@link Result#DUPLICATE}로 끝난다. 먼저 읽고 넣지 않는다(읽은 뒤 넣는 사이의 경쟁).</li>
 *   <li>트랜잭션: 부른 쪽 트랜잭션이 있으면 같이 쓰고, 없으면 새로 연다. 결제·청구 사건은 커밋 뒤 리스너가 새 트랜잭션에서 부르므로
 *       알림 저장 실패가 결제·청구를 되돌리지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
class NotificationWriter {

    /** {@code deduplication_key}의 최대 길이(VARCHAR(191) ascii). */
    static final int MAX_KEY_LENGTH = 191;
    static final int MAX_TITLE_LENGTH = 150;
    static final int MAX_BODY_LENGTH = 1000;

    enum Result {
        /** 새로 쌓았다 */
        CREATED,
        /** 같은 사건의 알림이 이미 있다 */
        DUPLICATE,
        /** 사용자가 그 묶음의 알림을 껐다 */
        DISABLED
    }

    /**
     * 쌓을 알림.
     *
     * @param deduplicationKey 같은 사건이면 같은 값. ASCII, 191자 이하(예: {@code PAYMENT_RESULT:1024:APPROVED})
     * @param targetType       앱 이동 대상 종류(앱 {@code refType}). 없으면 null이고 그때 targetId도 null
     */
    record Draft(long userId, NotificationType type, String deduplicationKey, String title, String body,
            String targetType, Long targetId) {

        Draft {
            if (deduplicationKey == null || deduplicationKey.isEmpty() || deduplicationKey.length() > MAX_KEY_LENGTH
                    || !StandardCharsets.US_ASCII.newEncoder().canEncode(deduplicationKey)) {
                throw new IllegalArgumentException("알림 중복 방지 키가 올바르지 않다: " + deduplicationKey);
            }
            if ((targetType == null) != (targetId == null)) {
                throw new IllegalArgumentException("알림 이동 대상은 종류와 번호가 같이 있어야 한다");
            }
            title = cut(title, MAX_TITLE_LENGTH);
            body = cut(body, MAX_BODY_LENGTH);
        }
    }

    private final NotificationSettingsStore settings;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    NotificationWriter(NotificationSettingsStore settings, NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 설정이 켜져 있으면 쌓는다. 사용자가 없으면 404 {@code RESOURCE_NOT_FOUND}, 설정 저장값이 깨졌으면 IllegalStateException이다.
     */
    Result write(Draft draft) {
        return transaction.execute(status -> {
            if (!enabled(settings.read(draft.userId()), draft.type())) {
                return Result.DISABLED;
            }
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            try {
                jdbc.update("""
                        INSERT INTO notification (user_id, notification_type, deduplication_key, title, body,
                                                  target_type, target_id, read_at, created_at, updated_at)
                        VALUES (:userId, :type, :key, :title, :body, :targetType, :targetId, NULL, :now, :now)
                        """, new MapSqlParameterSource("userId", draft.userId())
                        .addValue("type", draft.type().name())
                        .addValue("key", draft.deduplicationKey())
                        .addValue("title", draft.title())
                        .addValue("body", draft.body())
                        .addValue("targetType", draft.targetType())
                        .addValue("targetId", draft.targetId())
                        .addValue("now", now));
                return Result.CREATED;
            } catch (DuplicateKeyException e) {
                // 중복 키 실패는 그 문장만 실패한다(MySQL은 트랜잭션을 되돌리지 않는다). 예외를 밖으로 던지지 않으므로 롤백 표시도 없다
                return Result.DUPLICATE;
            }
        });
    }

    /** 알림 유형이 속한 설정 묶음이 켜져 있는지. 설정 묶음이 없는 유형은 늘 켜짐이다. */
    static boolean enabled(NotificationSettings settings, NotificationType type) {
        return switch (type) {
            case PERFORMANCE_SHORTFALL -> settings.performanceEnabled();
            case BALANCE_RISK -> settings.balanceRiskEnabled();
            case PAYMENT_RESULT, BILL_CONFIRMED, BILL_DUE_SOON, BILL_WITHDRAWN, BILL_UNPAID, REFUND_COMPLETED ->
                    settings.transactionEnabled();
            case CASHFLOW_CANDIDATE, BENEFIT_CHANGE, SECURITY -> true;
        };
    }

    /** 칸 길이를 넘는 문구는 코드포인트 기준으로 자른다. 가맹점·카드 별칭이 길어도 알림 저장이 실패하지 않게. */
    private static String cut(String text, int max) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("알림 제목·내용이 비어 있다");
        }
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }
}
