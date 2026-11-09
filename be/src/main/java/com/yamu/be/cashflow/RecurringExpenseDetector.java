package com.yamu.be.cashflow;

import com.yamu.be.card.CardQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제 이력(SEED 포함)에서 지금 매달 반복되는 지출을 찾아 가계부 후보(CANDIDATE)로 만든다(S15P31E103-349, 명세 「가계부 항목 목록」).
 *
 * <ul>
 *   <li>규칙: 생활비에서 반복 지출을 빼는 규칙과 같다({@link RecurringPaymentRule}). 같은 카드·가맹점·승인 금액이 이번 달과 앞 5개 달 중
 *       서로 다른 3개 달 이상에 나오고, 두 번 이상 나온 달이 없으면 후보다(2026-11-09 결정). 그래서 생활비에서 빠진 반복 지출이 후보로
 *       채워지고 두 번 빠지지 않는다. 금액이 조금씩 다른 정기 지출은 잡지 않는다.</li>
 *   <li>대상: 그 사용자의 승인(APPROVED) 결제 중 지금 등록된 카드의 것. 취소·0원 결제는 세지 않는다.</li>
 *   <li>후보: 이름은 가맹점 이름, 금액은 승인 금액, 매월 마지막 결제일의 날(없는 달은 말일)에 그 카드로 나간다. 첫 예정일은 마지막
 *       결제 다음 달부터 오늘 이후 첫 날이다.</li>
 *   <li>언제: 가계부 목록과 잔고예보를 조회할 때 부른다(2026-11-09 결정). 쓰기는 {@link RecurringExpenseStore}가 한다.</li>
 * </ul>
 *
 * <p>트랜잭션 밖에서 부른다(쓰기 트랜잭션을 따로 연다).
 */
@Slf4j
@Component
public class RecurringExpenseDetector {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 탐지 키 UNIQUE 제약 이름(V20261109_1). */
    private static final String DETECTION_KEY_CONSTRAINT = "uk_cashflow_item_detection";

    private final NamedParameterJdbcTemplate jdbc;
    private final CardQueryService cards;
    private final RecurringExpenseStore store;
    private final Clock clock;

    RecurringExpenseDetector(NamedParameterJdbcTemplate jdbc, CardQueryService cards, RecurringExpenseStore store,
            Clock clock) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.store = store;
        this.clock = clock;
    }

    /**
     * 사용자의 후보를 지금 결제 이력에 맞추고, 지금 잡힌 확인 전 후보의 탐지 근거를 돌려준다.
     *
     * @param userId 로그인 사용자
     * @return 후보 규칙 번호 → 탐지 근거
     */
    public Map<Long, Detection> refresh(Long userId) {
        LocalDate today = LocalDate.now(clock.withZone(KOREA));
        YearMonth month = YearMonth.from(today);
        LocalDate from = month.minusMonths(RecurringPaymentRule.WINDOW_MONTHS - 1).atDay(1);
        List<Payment> payments = jdbc.query("""
                SELECT p.user_card_id, p.merchant_id, p.approved_amount, p.approved_at,
                       m.merchant_name, m.merchant_category_code
                  FROM payment_transaction p
                  JOIN user_card uc ON uc.user_card_id = p.user_card_id AND uc.user_id = p.user_id
                  JOIN merchant m ON m.merchant_id = p.merchant_id
                 WHERE p.user_id = :userId AND p.status = 'APPROVED' AND p.approved_amount > 0
                   AND p.approved_at >= :from AND p.approved_at < :to
                 ORDER BY p.approved_at, p.payment_transaction_id
                """, new MapSqlParameterSource("userId", userId).addValue("from", startOfKoreanDay(from))
                        .addValue("to", startOfKoreanDay(today.plusDays(1))),
                (rs, rowNum) -> new Payment(new RecurringExpenseStore.Key(rs.getLong("user_card_id"),
                        rs.getLong("merchant_id"), rs.getLong("approved_amount")),
                        rs.getObject("approved_at", LocalDateTime.class), rs.getString("merchant_name"),
                        rs.getString("merchant_category_code")));

        Map<RecurringExpenseStore.Key, List<Payment>> byKey = new LinkedHashMap<>();
        payments.forEach(payment -> byKey.computeIfAbsent(payment.key(), k -> new ArrayList<>()).add(payment));
        List<RecurringExpenseStore.Detected> detected = new ArrayList<>();
        Map<RecurringExpenseStore.Key, Detection> detections = new HashMap<>();
        for (Map.Entry<RecurringExpenseStore.Key, List<Payment>> entry : byKey.entrySet()) {
            Map<YearMonth, Integer> counts = new HashMap<>();
            entry.getValue().forEach(p -> counts.merge(YearMonth.from(korean(p.approvedAt())), 1, Integer::sum));
            if (!RecurringPaymentRule.recurring(counts, month)
                    || cards.findRegisteredCard(userId, entry.getKey().userCardId()).isEmpty()) {
                continue;
            }
            // 결제는 승인 시각 순이라 마지막 줄이 마지막 결제다.
            Payment last = entry.getValue().get(entry.getValue().size() - 1);
            LocalDate lastPaid = korean(last.approvedAt());
            detected.add(new RecurringExpenseStore.Detected(entry.getKey(), last.merchantName(),
                    last.merchantCategoryCode(), nextOccurrence(lastPaid, today), lastPaid.getDayOfMonth()));
            long amount = entry.getKey().amount();
            detections.put(entry.getKey(), new Detection(last.merchantName(),
                    RecurringPaymentRule.monthsIn(counts, month), last.approvedAt(), new AmountRange(amount, amount)));
        }

        Map<Long, Detection> result = new HashMap<>();
        apply(userId, detected).forEach((itemId, key) -> result.put(itemId, detections.get(key)));
        return result;
    }

    /**
     * 같은 사용자의 두 요청이 같은 후보를 동시에 만들면 늦은 쪽이 탐지 키 UNIQUE에 걸린다. 그때는 앞 요청이 커밋한 뒤라 한 번 더 맞추면
     * 만들어진 후보를 읽는다. 다른 제약 위반은 그대로 던진다.
     */
    private Map<Long, RecurringExpenseStore.Key> apply(Long userId, List<RecurringExpenseStore.Detected> detected) {
        try {
            return store.apply(userId, detected);
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, DETECTION_KEY_CONSTRAINT)) {
                throw e;
            }
            log.info("반복 지출 후보를 다른 요청이 먼저 만들어 다시 맞춘다 userId={}", userId);
            return store.apply(userId, detected);
        }
    }

    private static boolean violates(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    /** 마지막 결제 다음 달부터 매월 그날(없는 달은 말일) 중 오늘 이후 첫 날. */
    static LocalDate nextOccurrence(LocalDate lastPaid, LocalDate today) {
        int day = lastPaid.getDayOfMonth();
        for (YearMonth month = YearMonth.from(lastPaid).plusMonths(1); ; month = month.plusMonths(1)) {
            LocalDate date = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (!date.isBefore(today)) {
                return date;
            }
        }
    }

    private static LocalDate korean(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).toLocalDate();
    }

    private static LocalDateTime startOfKoreanDay(LocalDate date) {
        return date.atStartOfDay(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 명세의 탐지 근거.
     *
     * @param merchantName 가맹점 이름
     * @param sampleCount  이번 달과 앞 5개 달 중 나온 달 수(달마다 한 번이라 결제 수와 같다)
     * @param lastPaidAt   마지막 결제 승인 시각(UTC)
     * @param amountRange  승인 금액 범위. 같은 금액만 같은 지출로 보므로 최소·최대가 같다
     */
    public record Detection(String merchantName, int sampleCount, LocalDateTime lastPaidAt, AmountRange amountRange) {}

    public record AmountRange(long min, long max) {}

    private record Payment(RecurringExpenseStore.Key key, LocalDateTime approvedAt, String merchantName,
            String merchantCategoryCode) {}
}
