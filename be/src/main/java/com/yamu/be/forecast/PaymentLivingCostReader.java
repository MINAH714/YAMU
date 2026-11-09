package com.yamu.be.forecast;

import com.yamu.be.cashflow.RecurringPaymentRule;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *       나오고, 그 6개월 중 두 번 이상 나온 달이 없으면(매달 한 번) 뺀다(분류·출처와 상관없이 셈, 취소 결제는 세지 않음).
 *       월세·구독료처럼 예정 지출로 따로 반영하는 정기 지출이 두 번 빠지지 않게 한다. 주 3회 4,500원 커피처럼 한 달에 여러 번 나오는
 *       같은 가격 소비는 생활비에 남긴다(S15P31E103-295).</li>
 *   <li><b>예정 지출과 연결된 결제 빼기</b>: 사용자가 예정 지출 회차와 연결한 결제({@code cashflow_payment_match})는 더하지 않는다.
 *       매주 반복 지출이나 금액이 바뀌는 정기 지출처럼 위 반복 판정에 걸리지 않는 결제가 예정 지출과 두 번 빠지지 않게 한다.
 *       반복 판정에는 그대로 센다(S15P31E103-319).</li>
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
 *
 * <p>신용카드도 같은 규칙으로 카드별로 읽는다({@link CreditSpendingReader}, S15P31E103-286). 반복 지출 키에 카드가 들어 있어
 * 체크카드와 신용카드의 반복 판정은 서로 섞이지 않는다.
 *
 * <p>읽기 전용 트랜잭션({@code @Transactional(readOnly = true)}) 안에서 부르지 말 것. 분류가 UPDATE를 해서 MySQL이 거부한다.
 */
@Component
class PaymentLivingCostReader implements LivingCostReader, CreditSpendingReader {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 반복 지출을 셀 기간(그 결제의 달 포함, 달 수). 반복 지출 탐지와 같은 규칙이다({@link RecurringPaymentRule}). */
    static final int RECURRING_WINDOW_MONTHS = RecurringPaymentRule.WINDOW_MONTHS;

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
        List<DailySpending> days = new ArrayList<>();
        for (Line line : lines(Group.CHECK_BY_ACCOUNT, userId, accountIds, List.copyOf(new LinkedHashSet<>(accountIds)),
                fromDate, toDate)) {
            days.add(new DailySpending(line.key(), line.date(), line.amount(), line.amount(), line.origin(), true));
        }
        return new Spending(List.copyOf(days), syncedAt);
    }

    /**
     * 선택 계좌가 지금 출금 계좌인 사용자 신용카드와 카드별 일별 LIVING 결제 합계(S15P31E103-286). 체크카드와 같은 규칙이다.
     * 등록 해제한 카드도 목록에 넣는다(쓸 수 있는 카드인지는 부르는 쪽이 BE 1로 확인한다).
     */
    @Override
    public CreditSpending creditDailySpending(Long userId, List<Long> accountIds, LocalDate fromDate,
            LocalDate toDate) {
        classifier.classify(userId);
        if (accountIds.isEmpty()) {
            return new CreditSpending(List.of(), List.of());
        }
        List<Long> cardIds = jdbc.queryForList("""
                SELECT uc.user_card_id
                  FROM user_card uc
                  JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                 WHERE uc.user_id = :userId AND uc.linked_account_id IN (:accountIds) AND cp.card_type = 'CREDIT'
                 ORDER BY uc.user_card_id
                """, new MapSqlParameterSource("userId", userId).addValue("accountIds", accountIds), Long.class);
        if (cardIds.isEmpty() || fromDate.isAfter(toDate)) {
            return new CreditSpending(List.copyOf(cardIds), List.of());
        }
        List<CardDailySpending> days = new ArrayList<>();
        for (Line line : lines(Group.CREDIT_BY_CARD, userId, accountIds, cardIds, fromDate, toDate)) {
            days.add(new CardDailySpending(line.key(), line.date(), line.amount(), line.origin()));
        }
        return new CreditSpending(List.copyOf(cardIds), List.copyOf(days));
    }

    /**
     * 묶음(계좌 또는 카드)·날짜·출처별 LIVING 결제 합계. 출처마다 그 출처가 덮는 기간의 모든 날에 한 줄이다.
     *
     * @param keys 줄을 줄 묶음 번호(계좌 번호 또는 카드 번호). 순서대로 담는다
     */
    private List<Line> lines(Group group, Long userId, List<Long> accountIds, List<Long> keys, LocalDate fromDate,
            LocalDate toDate) {
        // 묶음 칸 이름·카드 종류는 Group 상수에서만 오고 사용자 입력이 아니다.
        String key = group.keyColumn;
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("accountIds", accountIds)
                .addValue("cardType", group.cardType);
        Map<Long, LocalDate> registeredOn = new HashMap<>();
        jdbc.query("""
                SELECT %s AS group_key, MIN(uc.registered_at) AS started_at
                  FROM user_card uc
                  JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                 WHERE uc.user_id = :userId AND uc.linked_account_id IN (:accountIds)
                   AND cp.card_type = :cardType AND uc.registered_at IS NOT NULL
                 GROUP BY %s
                """.formatted(key, key), params, rs -> {
            registeredOn.put(rs.getLong("group_key"), korean(rs.getObject("started_at", LocalDateTime.class)));
        });
        // 출처별 첫 결제일. YAMU 첫 결제일이 REAL 시작, SEED 첫 결제일이 SEED 시작이다.
        Map<Long, LocalDate> realStart = new HashMap<>();
        Map<Long, LocalDate> seedStart = new HashMap<>();
        jdbc.query("""
                SELECT %s AS group_key, p.source, MIN(p.approved_at) AS started_at
                  FROM payment_transaction p
                  JOIN user_card uc ON uc.user_card_id = p.user_card_id AND uc.user_id = p.user_id
                  JOIN card_product cp ON cp.card_product_id = p.card_product_id
                 WHERE p.user_id = :userId AND uc.linked_account_id IN (:accountIds)
                   AND cp.card_type = :cardType AND p.status = 'APPROVED'
                 GROUP BY %s, p.source
                """.formatted(key, key), params, rs -> {
            Map<Long, LocalDate> target = "SEED".equals(rs.getString("source")) ? seedStart : realStart;
            target.put(rs.getLong("group_key"), korean(rs.getObject("started_at", LocalDateTime.class)));
        });

        // fromDate가 든 달의 결제도 앞 5개 달을 봐야 반복 지출을 셀 수 있어 그만큼 앞에서부터 읽는다.
        LocalDate readFrom = YearMonth.from(fromDate).minusMonths(RECURRING_WINDOW_MONTHS - 1).atDay(1);
        List<Payment> payments = jdbc.query("""
                SELECT %s AS group_key, p.user_card_id, p.merchant_id, p.approved_amount, p.approved_at,
                       p.source, p.spending_class,
                       EXISTS (SELECT 1 FROM cashflow_payment_match m
                                WHERE m.payment_transaction_id = p.payment_transaction_id) AS matched
                  FROM payment_transaction p
                  JOIN user_card uc ON uc.user_card_id = p.user_card_id AND uc.user_id = p.user_id
                  JOIN card_product cp ON cp.card_product_id = p.card_product_id
                 WHERE p.user_id = :userId AND uc.linked_account_id IN (:accountIds)
                   AND cp.card_type = :cardType AND p.status = 'APPROVED'
                   AND p.approved_at >= :from AND p.approved_at < :to
                """.formatted(key), params.addValue("from", startOfKoreanDay(readFrom))
                        .addValue("to", startOfKoreanDay(toDate.plusDays(1))),
                (rs, rowNum) -> new Payment(rs.getLong("group_key"), rs.getLong("user_card_id"),
                        rs.getLong("merchant_id"), rs.getLong("approved_amount"),
                        korean(rs.getObject("approved_at", LocalDateTime.class)),
                        "SEED".equals(rs.getString("source")) ? Origin.SEED : Origin.REAL,
                        "LIVING".equals(rs.getString("spending_class")), rs.getBoolean("matched")));

        Map<RecurringKey, Map<YearMonth, Integer>> countsByKey = new HashMap<>();
        for (Payment payment : payments) {
            countsByKey.computeIfAbsent(payment.recurringKey(), k -> new HashMap<>())
                    .merge(YearMonth.from(payment.date()), 1, Integer::sum);
        }

        List<Line> lines = new ArrayList<>();
        for (Long groupKey : keys) {
            Map<Origin, Range> coverage = coverage(realStart.get(groupKey), registeredOn.get(groupKey),
                    seedStart.get(groupKey), fromDate, toDate);
            Map<Origin, Map<LocalDate, Long>> sums = new EnumMap<>(Origin.class);
            for (Payment payment : payments) {
                Range range = coverage.get(payment.origin());
                // 예정 지출과 연결된 결제는 그 예정 지출로 이미 반영돼 더하지 않는다(반복 판정에는 센다).
                if (payment.groupKey() != groupKey.longValue() || !payment.living() || payment.matched()
                        || range == null
                        || !range.contains(payment.date())
                        || RecurringPaymentRule.recurring(countsByKey.get(payment.recurringKey()),
                                YearMonth.from(payment.date()))) {
                    continue;
                }
                sums.computeIfAbsent(payment.origin(), k -> new TreeMap<>())
                        .merge(payment.date(), payment.amount(), Math::addExact);
            }
            coverage.forEach((origin, range) -> {
                Map<LocalDate, Long> byDate = sums.getOrDefault(origin, Map.of());
                for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
                    lines.add(new Line(groupKey, date, byDate.getOrDefault(date, 0L), origin));
                }
            });
        }
        return lines;
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

    /** 줄을 묶는 단위. 체크카드는 계좌별(생활비), 신용카드는 카드별(마감 전 사용 추정)이다. */
    private enum Group {
        CHECK_BY_ACCOUNT("uc.linked_account_id", "CHECK"),
        CREDIT_BY_CARD("uc.user_card_id", "CREDIT");

        private final String keyColumn;
        private final String cardType;

        Group(String keyColumn, String cardType) {
            this.keyColumn = keyColumn;
            this.cardType = cardType;
        }
    }

    /** 묶음(계좌 또는 카드) 하나의 하루·출처별 합계. */
    private record Line(long key, LocalDate date, long amount, Origin origin) {}

    /** @param groupKey 묶음 번호(체크카드는 카드의 출금 계좌, 신용카드는 카드 번호) */
    private record Payment(long groupKey, long userCardId, long merchantId, long amount, LocalDate date,
            Origin origin, boolean living, boolean matched) {
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
