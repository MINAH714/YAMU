package com.yamu.be.payment.ledger;

import com.yamu.be.payment.cancel.CancellationLedger;
import com.yamu.be.payment.cancel.PaymentCancelResponse;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 취소 원장 복구(Task 5.2.5, POL-006·POL-009, 「결제 상태·예외 처리」 6장). 결제 행을 CANCELED로 바꾼 같은 트랜잭션 안에서 불린다.
 *
 * <pre>
 * 실적  performance_reversed_at이 비어 있을 때만: 승인 당시 실적 인정액을 원래 실적 월에서 빼고 시각을 적는다(0원이어도 시각은 적는다)
 * 한도  restored_at이 비어 있는 payment_limit_usage 행마다: restored_at을 적고, MONTHLY면 원래 사용 월의 monthly_limit_usage에서 사용량을 뺀다.
 *       DAILY·PER_TRANSACTION은 일별 합계 테이블이 없으므로(FR-060) 시각만 적는다(추천은 복구되지 않은 행만 더한다)
 * payment_benefit은 지우지 않는다. 취소 여부는 결제 상태로 본다
 * </pre>
 *
 * <ul>
 *   <li>두 번 되돌리지 않음: 실적은 시각 확인 + 엔티티 메서드, 한도는 {@code restored_at IS NULL} 조건부 갱신으로 한 행씩 한 번만.</li>
 *   <li>지난달 결제의 취소도 원래 월에서 뺀다(POL-009). 이미 받은 혜택은 회수하지 않는다(정책 Q17 제안, 임시).</li>
 *   <li>합계가 빼려는 값보다 작거나 행이 없으면(데이터 불일치) 0 아래로 내리지 않고 경고만 남긴다. 취소를 막지 않는다(체크카드는 이미 환불 입금이 끝났다).</li>
 *   <li>정책 버전이 바뀌어도 사용 당시 한도 그룹 번호로 되돌린다(implementation.md).</li>
 * </ul>
 */
@Slf4j
@Component
public class JdbcCancellationLedger implements CancellationLedger {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCancellationLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PaymentCancelResponse.Restored reverse(PaymentTransaction payment, LocalDateTime canceledAt) {
        PaymentCancelResponse.Performance performance = reversePerformance(payment, canceledAt);
        List<PaymentCancelResponse.LimitImpact> limits = restoreLimits(payment, canceledAt);
        log.info("취소 원장 복구 paymentId={} performance={} limitGroups={}", payment.getId(),
                performance == null ? 0 : performance.amount(), limits.size());
        return new PaymentCancelResponse.Restored(performance, limits);
    }

    private PaymentCancelResponse.Performance reversePerformance(PaymentTransaction payment, LocalDateTime now) {
        if (payment.getPerformanceReversedAt() != null) {
            return null;
        }
        long amount = payment.getRecognizedPerformanceAmount() == null ? 0 : payment.getRecognizedPerformanceAmount();
        LocalDate month = payment.getPerformanceMonth();
        if (amount > 0 && month != null) {
            subtract("card_monthly_performance", "recognized_amount",
                    "user_card_id = :card AND performance_month = :month",
                    new MapSqlParameterSource().addValue("card", payment.getUserCardId()).addValue("month", month),
                    amount, now, payment.getId());
        }
        payment.markPerformanceReversed(now);
        return amount > 0 && month != null
                ? new PaymentCancelResponse.Performance(YearMonth.from(month).toString(), amount)
                : null;
    }

    private List<PaymentCancelResponse.LimitImpact> restoreLimits(PaymentTransaction payment, LocalDateTime now) {
        List<UsageRow> rows = jdbc.query("""
                SELECT u.payment_limit_usage_id, u.limit_group_id, u.consumed_value, u.usage_month, g.period_type
                FROM payment_limit_usage u
                JOIN limit_group g ON g.limit_group_id = u.limit_group_id
                WHERE u.payment_transaction_id = :payment AND u.restored_at IS NULL
                ORDER BY u.payment_limit_usage_id
                FOR UPDATE
                """, new MapSqlParameterSource("payment", payment.getId()),
                (rs, i) -> new UsageRow(rs.getLong("payment_limit_usage_id"), rs.getLong("limit_group_id"),
                        rs.getLong("consumed_value"), rs.getObject("usage_month", LocalDate.class),
                        rs.getString("period_type")));
        List<PaymentCancelResponse.LimitImpact> restored = new ArrayList<>();
        for (UsageRow row : rows) {
            int marked = jdbc.update("""
                    UPDATE payment_limit_usage SET restored_at = :now, updated_at = :now, version = version + 1
                    WHERE payment_limit_usage_id = :id AND restored_at IS NULL
                    """, new MapSqlParameterSource().addValue("id", row.id()).addValue("now", now));
            if (marked != 1) {
                continue;
            }
            if (LedgerDates.MONTHLY.equals(row.period()) && row.consumed() > 0) {
                subtract("monthly_limit_usage", "used_value",
                        "user_card_id = :card AND limit_group_id = :group AND usage_month = :month",
                        new MapSqlParameterSource().addValue("card", payment.getUserCardId())
                                .addValue("group", row.limitGroupId()).addValue("month", row.usageMonth()),
                        row.consumed(), now, payment.getId());
            }
            restored.add(new PaymentCancelResponse.LimitImpact(row.limitGroupId(), row.period(),
                    YearMonth.from(row.usageMonth()).toString(), row.consumed()));
        }
        return restored;
    }

    /**
     * 합계 행에서 값을 뺀다. 행을 잠가 읽고 0 아래로 내리지 않는다. 행이 없거나 값이 모자라면 데이터가 어긋난 것이므로 경고를 남긴다.
     * 표·칸 이름은 이 클래스의 상수만 넘긴다(사용자 입력 아님).
     */
    private void subtract(String table, String column, String where, MapSqlParameterSource keys, long amount,
            LocalDateTime now, Long paymentId) {
        List<Long> current = jdbc.queryForList("SELECT " + column + " FROM " + table + " WHERE " + where + " FOR UPDATE",
                keys, Long.class);
        if (current.isEmpty()) {
            log.warn("취소로 되돌릴 합계 행이 없음(데이터 불일치), 운영 확인 paymentId={} table={} amount={}", paymentId, table,
                    amount);
            return;
        }
        if (current.get(0) < amount) {
            log.warn("취소로 되돌릴 값이 합계보다 큼(데이터 불일치), 0으로 맞춤 paymentId={} table={} current={} amount={}",
                    paymentId, table, current.get(0), amount);
        }
        jdbc.update("UPDATE " + table + " SET " + column + " = GREATEST(" + column + " - :amount, 0), updated_at = :now, "
                + "version = version + 1 WHERE " + where, keys.addValue("amount", amount).addValue("now", now));
    }

    private record UsageRow(long id, long limitGroupId, long consumed, LocalDate usageMonth, String period) {}
}
