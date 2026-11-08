package com.yamu.be.forecast;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제의 소비 분류({@code payment_transaction.spending_class})를 자동으로 채운다(BE 4, 2026-11-04 BE 2 합의).
 * 승인 코드는 건드리지 않고, 생활비 원천({@link PaymentLivingCostReader})이 읽기 직전에 그 사용자의 미분류 결제만 분류한다.
 * 결제 내역 목록·CSV(BE 2, S15P31E103-300·302)도 같은 방식으로 읽기 직전에 부른다(2026-11-06 BE 4 결정).
 *
 * <p>규칙(명세 「소비 분류 수정」의 자동 분류 가정 + 2026-11-03 큰 일회성 지출 기준). 신용·체크 모두 같다.
 * <ul>
 *   <li>LARGE: 할부({@code installment_months >= 2}), 또는 한 건 {@code approved_amount > 1,000,000}원,
 *       또는 그 결제 직전 3개월(승인 시각 기준) LIVING 결제 건당 중앙값의 10배 이상.
 *       중앙값 기준은 직전 LIVING 결제가 {@value #MIN_MEDIAN_SAMPLES}건 이상이고 중앙값이 0원보다 클 때만 쓴다(표본 수는 임시).</li>
 *   <li>나머지는 LIVING. 출처는 AUTO.</li>
 *   <li>승인(APPROVED) 결제만 다룬다. 취소 결제와 실적 제외(EXCLUDED)는 규칙이 없어 다루지 않는다.</li>
 * </ul>
 *
 * <p>쓰기는 {@code spending_class IS NULL}일 때만 한다. 그래서 사용자가 고친 값(USER)을 덮지 않고, 두 요청이 동시에 분류해도
 * 한 번만 써진다(같은 데이터로 같은 값을 계산한다). JPA {@code version}은 올리지 않는다. 올리면 같은 결제를 고치는 BE 2 흐름(취소 등)이
 * 낙관적 잠금 충돌로 실패하는데, 반대로 그 흐름이 옛 값(NULL)으로 덮어도 AUTO 값은 다음 읽기에서 같은 값으로 다시 분류되기 때문이다.
 * USER 값은 다시 만들 수 없어, 엔티티 저장이 모든 컬럼을 덮지 않게 하는 것은 BE 2가 맡는다({@code @DynamicUpdate}, Task 204 검토).
 *
 * <p>읽기 전용 트랜잭션({@code @Transactional(readOnly = true)}) 안에서 부르지 말 것. UPDATE를 해서 MySQL이 거부한다.
 */
@Component
public class SpendingClassifier {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 한 건 이 금액을 넘으면 큰 일회성 지출이다(2026-11-03 결정, 생활비 원천과 같은 기준). */
    static final long LARGE_AMOUNT = 1_000_000L;
    /** 직전 3개월 LIVING 건당 중앙값의 이 배수 이상이면 LARGE다(명세 「소비 분류 수정」). */
    static final long MEDIAN_MULTIPLE = 10L;
    /** 중앙값 기준을 쓰기 위한 직전 LIVING 결제 수. 임시(2026-11-04): 몇 건으로 중앙값을 믿을지 정한 근거가 없다. */
    static final int MIN_MEDIAN_SAMPLES = 5;
    static final int MEDIAN_MONTHS = 3;
    /** 한 UPDATE에 넣을 결제 번호 수. SQL 길이를 적당히 두려고 나눈다. */
    private static final int WRITE_CHUNK = 1_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    SpendingClassifier(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 그 사용자의 미분류 승인 결제를 승인 시각 순서로 LIVING·LARGE로 분류한다. 앞 결제의 분류가 뒤 결제의 중앙값 표본이 된다.
     *
     * @return 이번에 새로 쓴 결제 수(다른 요청이 먼저 썼거나 그 사이 사용자가 고친 결제는 세지 않는다)
     */
    public int classify(Long userId) {
        MapSqlParameterSource user = new MapSqlParameterSource("userId", userId);
        List<Unclassified> targets = jdbc.query("""
                SELECT payment_transaction_id, approved_amount, installment_months, approved_at
                  FROM payment_transaction
                 WHERE user_id = :userId AND status = 'APPROVED' AND spending_class IS NULL
                 ORDER BY approved_at, payment_transaction_id
                """, user, (rs, rowNum) -> new Unclassified(rs.getLong("payment_transaction_id"),
                rs.getLong("approved_amount"), rs.getInt("installment_months"),
                rs.getObject("approved_at", LocalDateTime.class)));
        if (targets.isEmpty()) {
            return 0;
        }
        // 표본: 첫 대상의 3개월 전부터 마지막 대상 전까지의 LIVING 결제(사용자 분류 포함).
        List<Living> living = jdbc.query("""
                SELECT payment_transaction_id, approved_amount, approved_at
                  FROM payment_transaction
                 WHERE user_id = :userId AND status = 'APPROVED' AND spending_class = 'LIVING'
                   AND approved_at >= :from AND approved_at < :to
                 ORDER BY approved_at
                """, user.addValue("from", windowStart(targets.get(0).approvedAt()))
                        .addValue("to", targets.get(targets.size() - 1).approvedAt()),
                (rs, rowNum) -> new Living(rs.getLong("payment_transaction_id"), rs.getLong("approved_amount"),
                        rs.getObject("approved_at", LocalDateTime.class)));

        // 한 건씩 쓰면 결제 수만큼 DB를 오가 미분류 3,000건에 30초 넘게 걸렸다(2026-11-04 측정). 분류별로 묶어 쓴다.
        Map<String, List<Long>> idsByClass = new LinkedHashMap<>();
        decide(targets, living).forEach((id, spendingClass) ->
                idsByClass.computeIfAbsent(spendingClass, key -> new ArrayList<>()).add(id));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        int written = 0;
        for (Map.Entry<String, List<Long>> group : idsByClass.entrySet()) {
            List<Long> ids = group.getValue();
            for (int from = 0; from < ids.size(); from += WRITE_CHUNK) {
                written += jdbc.update("""
                        UPDATE payment_transaction
                           SET spending_class = :spendingClass, spending_class_source = 'AUTO', updated_at = :now
                         WHERE payment_transaction_id IN (:ids) AND user_id = :userId
                           AND status = 'APPROVED' AND spending_class IS NULL
                        """, new MapSqlParameterSource("spendingClass", group.getKey()).addValue("now", now)
                        .addValue("ids", ids.subList(from, Math.min(from + WRITE_CHUNK, ids.size())))
                        .addValue("userId", userId));
            }
        }
        return written;
    }

    /**
     * DB 없이 대상마다 분류를 정한다. 대상은 승인 시각 순서이고, LIVING으로 정한 대상은 뒤 대상의 표본에 더한다.
     *
     * <p>대상과 표본을 따로 읽으므로, 그 사이 다른 요청이 대상 하나를 LIVING으로 쓰면 그 결제가 표본에도 온다. 그대로 두면
     * 한 결제를 두 번 세어 중앙값이 바뀌므로, 대상인 결제는 표본에서 빼고 이번 계산 결과로만 더한다. 그래서 실행이 겹쳐도
     * 같은 결과가 나온다.
     *
     * @param targets 미분류 결제(승인 시각 순서)
     * @param living  이미 LIVING인 결제
     * @return 결제 번호별 분류(LIVING·LARGE), 대상 순서
     */
    static Map<Long, String> decide(List<Unclassified> targets, List<Living> living) {
        Set<Long> targetIds = new HashSet<>();
        targets.forEach(target -> targetIds.add(target.id()));
        List<Living> sample = new ArrayList<>();
        living.stream().filter(row -> !targetIds.contains(row.id())).forEach(sample::add);
        Map<Long, String> decisions = new LinkedHashMap<>();
        for (Unclassified target : targets) {
            String spendingClass = isLarge(target, sample) ? "LARGE" : "LIVING";
            decisions.put(target.id(), spendingClass);
            if (spendingClass.equals("LIVING")) {
                sample.add(new Living(target.id(), target.amount(), target.approvedAt()));
            }
        }
        return decisions;
    }

    private static boolean isLarge(Unclassified target, List<Living> living) {
        if (target.installmentMonths() >= 2 || target.amount() > LARGE_AMOUNT) {
            return true;
        }
        LocalDateTime from = windowStart(target.approvedAt());
        List<Long> sample = living.stream()
                .filter(row -> !row.approvedAt().isBefore(from) && row.approvedAt().isBefore(target.approvedAt()))
                .map(Living::amount)
                .sorted()
                .toList();
        if (sample.size() < MIN_MEDIAN_SAMPLES) {
            return false;
        }
        int middle = sample.size() / 2;
        // 짝수 건이면 가운데 두 값의 평균이라, 소수점 없이 비교하려고 중앙값의 두 배로 비교한다.
        long doubledMedian = sample.size() % 2 == 1
                ? Math.multiplyExact(sample.get(middle), 2L)
                : Math.addExact(sample.get(middle - 1), sample.get(middle));
        // 중앙값이 0원이면(전액 할인 결제만 있음) 모든 결제가 10배 이상이 되므로 이 기준을 쓰지 않는다.
        if (doubledMedian == 0) {
            return false;
        }
        return Math.multiplyExact(target.amount(), 2L) >= Math.multiplyExact(doubledMedian, MEDIAN_MULTIPLE);
    }

    /** 승인 시각의 3개월 전(한국 달력으로 계산, UTC로 돌려줌). 11/30 결제면 8/30이다. */
    private static LocalDateTime windowStart(LocalDateTime approvedAtUtc) {
        return approvedAtUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).minusMonths(MEDIAN_MONTHS)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** 미분류 승인 결제. approvedAt은 UTC. */
    record Unclassified(long id, long amount, int installmentMonths, LocalDateTime approvedAt) {}

    /** 이미 LIVING인 승인 결제. approvedAt은 UTC. */
    record Living(long id, long amount, LocalDateTime approvedAt) {}
}
