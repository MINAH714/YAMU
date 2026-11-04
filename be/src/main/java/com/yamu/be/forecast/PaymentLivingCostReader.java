package com.yamu.be.forecast;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 생활비 원천. 선택 계좌에 연결된 YAMU 체크카드의 LIVING 결제를 한국 날짜별로 더한다({@link LivingCostReader} 계약).
 * 읽기 직전에 그 사용자의 미분류 결제를 {@link SpendingClassifier}로 분류한다. 소비 분류와 원천은 BE 4가 만들고 BE 2가 리뷰한다
 * (2026-11-04 합의).
 *
 * <ul>
 *   <li><b>넣는 것</b>: 카드의 지금 출금 계좌({@code user_card.linked_account_id})가 선택 계좌인 체크카드
 *       ({@code card_product.card_type = 'CHECK'})의 승인(APPROVED)·LIVING 결제. 신용카드(청구로 반영), LARGE·EXCLUDED·미분류,
 *       취소 결제는 넣지 않는다. 등록 해제한 카드의 과거 결제도 넣는다(2026-11-04 결정).</li>
 *   <li><b>반복 지출 빼기</b>: 그 결제의 달과 앞 5개 달(6개월) 안에서 같은 카드·가맹점·승인 금액의 승인 결제가 서로 다른 3개 달 이상에
 *       나오면 뺀다(분류·출처와 상관없이 셈). 월세·구독료처럼 예정 지출로 따로 반영하는 정기 지출이 두 번 빠지지 않게 한다.</li>
 *   <li><b>출처별 기간(계좌마다)</b>: REAL은 그 계좌 체크카드의 첫 YAMU 결제일(한국 날짜)부터 toDate까지, SEED는 그 계좌
 *       체크카드의 첫 SEED 결제일부터 가장 이른 등록일 전날(등록한 카드가 없으면 toDate)까지 매일 한 줄을 준다(2026-11-04 결정).
 *       등록만 하고 결제하지 않은 기간은 YAMU를 쓰지 않은 것인지 알 수 없어 줄을 주지 않는다(0원으로 확정하지 않음).
 *       각 줄에는 그 출처의 결제만 더한다(REAL = {@code source='YAMU'}, SEED = {@code source='SEED'}). 그 출처 기간 밖에 찍힌
 *       결제는 버린다. fromDate 앞·toDate 뒤 날짜는 주지 않는다. 0원 날도 줄을 주고 모두 {@code complete=true}다.</li>
 *   <li>{@code amount = checkCardAmount}. 계좌 출금은 분류 규칙이 없어 넣지 않는다(docs/be/forecast.md 「계좌 출금은 지금 넣지 않음」).</li>
 * </ul>
 *
 * <p>한계: 한 계좌에 체크카드가 여럿이면 REAL은 그중 가장 이른 첫 결제일부터라, 늦게 쓰기 시작한 카드의 그 전 소비는 모른다.
 * 첫 결제 뒤에 YAMU를 쓰지 않게 된 날도 0원으로 센다.
 */
@Component
class PaymentLivingCostReader implements LivingCostReader {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 반복 지출을 셀 기간(그 결제의 달 포함, 달 수). */
    static final int RECURRING_WINDOW_MONTHS = 6;
    /** 이 수 이상의 서로 다른 달에 나오면 반복 지출이다. */
    static final int RECURRING_MIN_MONTHS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final SpendingClassifier classifier;
    private final Clock clock;

    PaymentLivingCostReader(NamedParameterJdbcTemplate jdbc, SpendingClassifier classifier, Clock clock) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.clock = clock;
    }

    @Override
    public Spending dailySpending(Long userId, List<Long> accountIds, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime syncedAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        classifier.classify(userId);
        if (accountIds.isEmpty() || fromDate.isAfter(toDate)) {
            return new Spending(List.of(), syncedAt);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("accountIds", accountIds);
        Map<Long, LocalDate> registeredOn = new HashMap<>();
        jdbc.query("""
                SELECT uc.linked_account_id, MIN(uc.registered_at) AS started_at
                  FROM user_card uc
                  JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                 WHERE uc.user_id = :userId AND uc.linked_account_id IN (:accountIds)
                   AND cp.card_type = 'CHECK' AND uc.registered_at IS NOT NULL
                 GROUP BY uc.linked_account_id
                """, params, rs -> {
            registeredOn.put(rs.getLong("linked_account_id"),
                    korean(rs.getObject("started_at", LocalDateTime.class)));
        });
        // 출처별 첫 결제일. YAMU 첫 결제일이 REAL 시작, SEED 첫 결제일이 SEED 시작이다.
        Map<Long, LocalDate> realStart = new HashMap<>();
        Map<Long, LocalDate> seedStart = new HashMap<>();
        jdbc.query("""
                SELECT uc.linked_account_id, p.source, MIN(p.approved_at) AS started_at
                  FROM payment_transaction p
                  JOIN user_card uc ON uc.user_card_id = p.user_card_id AND uc.user_id = p.user_id
                  JOIN card_product cp ON cp.card_product_id = p.card_product_id
                 WHERE p.user_id = :userId AND uc.linked_account_id IN (:accountIds)
                   AND cp.card_type = 'CHECK' AND p.status = 'APPROVED'
                 GROUP BY uc.linked_account_id, p.source
                """, params, rs -> {
            Map<Long, LocalDate> target = "SEED".equals(rs.getString("source")) ? seedStart : realStart;
            target.put(rs.getLong("linked_account_id"), korean(rs.getObject("started_at", LocalDateTime.class)));
        });

        // fromDate가 든 달의 결제도 앞 5개 달을 봐야 반복 지출을 셀 수 있어 그만큼 앞에서부터 읽는다.
        LocalDate readFrom = YearMonth.from(fromDate).minusMonths(RECURRING_WINDOW_MONTHS - 1).atDay(1);
        List<Payment> payments = jdbc.query("""
                SELECT uc.linked_account_id, p.user_card_id, p.merchant_id, p.approved_amount, p.approved_at,
                       p.source, p.spending_class
                  FROM payment_transaction p
                  JOIN user_card uc ON uc.user_card_id = p.user_card_id AND uc.user_id = p.user_id
                  JOIN card_product cp ON cp.card_product_id = p.card_product_id
                 WHERE p.user_id = :userId AND uc.linked_account_id IN (:accountIds)
                   AND cp.card_type = 'CHECK' AND p.status = 'APPROVED'
                   AND p.approved_at >= :from AND p.approved_at < :to
                """, params.addValue("from", startOfKoreanDay(readFrom))
                        .addValue("to", startOfKoreanDay(toDate.plusDays(1))),
                (rs, rowNum) -> new Payment(rs.getLong("linked_account_id"), rs.getLong("user_card_id"),
                        rs.getLong("merchant_id"), rs.getLong("approved_amount"),
                        korean(rs.getObject("approved_at", LocalDateTime.class)),
                        "SEED".equals(rs.getString("source")) ? Origin.SEED : Origin.REAL,
                        "LIVING".equals(rs.getString("spending_class"))));

        Map<RecurringKey, Set<YearMonth>> monthsByKey = new HashMap<>();
        for (Payment payment : payments) {
            monthsByKey.computeIfAbsent(payment.recurringKey(), key -> new HashSet<>())
                    .add(YearMonth.from(payment.date()));
        }

        List<DailySpending> days = new ArrayList<>();
        for (Long accountId : new LinkedHashSet<>(accountIds)) {
            Map<Origin, Range> coverage = coverage(realStart.get(accountId), registeredOn.get(accountId),
                    seedStart.get(accountId), fromDate, toDate);
            Map<Origin, Map<LocalDate, Long>> sums = new EnumMap<>(Origin.class);
            for (Payment payment : payments) {
                Range range = coverage.get(payment.origin());
                if (payment.accountId() != accountId.longValue() || !payment.living() || range == null
                        || !range.contains(payment.date())
                        || recurring(monthsByKey.get(payment.recurringKey()), YearMonth.from(payment.date()))) {
                    continue;
                }
                sums.computeIfAbsent(payment.origin(), key -> new TreeMap<>())
                        .merge(payment.date(), payment.amount(), Math::addExact);
            }
            coverage.forEach((origin, range) -> {
                Map<LocalDate, Long> byDate = sums.getOrDefault(origin, Map.of());
                for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
                    long amount = byDate.getOrDefault(date, 0L);
                    days.add(new DailySpending(accountId, date, amount, amount, origin, true));
                }
            });
        }
        return new Spending(List.copyOf(days), syncedAt);
    }

    /**
     * 그 계좌에서 출처마다 줄을 줄 기간. 덮는 날이 없는 출처는 넣지 않는다.
     *
     * @param realStart    그 계좌 체크카드의 첫 YAMU 결제일. 없으면 REAL 줄을 주지 않는다
     * @param registeredOn 그 계좌 체크카드의 가장 이른 등록일. SEED는 그 전날까지다. 없으면 toDate까지
     */
    private static Map<Origin, Range> coverage(LocalDate realStart, LocalDate registeredOn, LocalDate seedStart,
            LocalDate fromDate, LocalDate toDate) {
        Map<Origin, Range> coverage = new EnumMap<>(Origin.class);
        if (seedStart != null) {
            LocalDate seedEnd = registeredOn == null ? toDate : min(registeredOn.minusDays(1), toDate);
            if (realStart != null) {
                // 등록 전 YAMU 결제는 생기지 않지만, 생겨도 같은 날을 두 출처가 덮지 않게 한다.
                seedEnd = min(seedEnd, realStart.minusDays(1));
            }
            Range.of(max(seedStart, fromDate), seedEnd).ifPresent(range -> coverage.put(Origin.SEED, range));
        }
        if (realStart != null) {
            Range.of(max(realStart, fromDate), toDate).ifPresent(range -> coverage.put(Origin.REAL, range));
        }
        return coverage;
    }

    /** 그 달과 앞 5개 달 중 같은 카드·가맹점·금액이 나온 서로 다른 달이 3개 이상인지. */
    private static boolean recurring(Set<YearMonth> months, YearMonth month) {
        YearMonth first = month.minusMonths(RECURRING_WINDOW_MONTHS - 1);
        long count = months.stream().filter(candidate -> !candidate.isBefore(first) && !candidate.isAfter(month))
                .count();
        return count >= RECURRING_MIN_MONTHS;
    }

    private static LocalDate korean(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).toLocalDate();
    }

    private static LocalDateTime startOfKoreanDay(LocalDate date) {
        return date.atStartOfDay(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private record Payment(long accountId, long userCardId, long merchantId, long amount, LocalDate date,
            Origin origin, boolean living) {
        RecurringKey recurringKey() {
            return new RecurringKey(userCardId, merchantId, amount);
        }
    }

    private record RecurringKey(long userCardId, long merchantId, long amount) {}

    private record Range(LocalDate from, LocalDate to) {
        static Optional<Range> of(LocalDate from, LocalDate to) {
            return from.isAfter(to) ? Optional.empty() : Optional.of(new Range(from, to));
        }

        boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }
}
