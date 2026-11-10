package com.yamu.be.performance;

import com.yamu.be.performance.CardPerformancesResponse.RecentChange;
import java.sql.Date;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 실적 화면용 결제 원장 조회. 조회 전용이라 엔티티를 거치지 않는다.
 *
 * <p>모든 쿼리에 {@code user_id}를 넣어 남의 결제를 읽지 않는다. 실적 월은 결제 행의 {@code performance_month}(승인 때
 * 정한 인정 월, 승인 결제는 NOT NULL)로 고른다. 그달 실적 합계는 카드 패키지의 {@code CardActivityReader}가 읽는다.
 */
@Component
class PerformanceReader {

    private final NamedParameterJdbcTemplate jdbc;

    PerformanceReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그달 실적 인정액이 미확인(NULL)인 승인 결제가 있는 카드. */
    Set<Long> cardsWithUnconfirmed(long userId, Collection<Long> userCardIds, YearMonth month) {
        Set<Long> cards = new HashSet<>();
        if (userCardIds.isEmpty()) {
            return cards;
        }
        jdbc.query("""
                select distinct p.user_card_id from payment_transaction p
                where p.user_id = :userId and p.user_card_id in (:ids)
                  and p.status = 'APPROVED' and p.performance_month = :month
                  and p.recognized_performance_amount is null
                """, params(userId, userCardIds, month), rs -> {
                    cards.add(rs.getLong(1));
                });
        return cards;
    }

    /** 카드별로 그달 실적에 가장 최근 반영된 승인 결제(인정액 > 0). 취소된 결제는 빠진다. */
    Map<Long, RecentChange> recentChanges(long userId, Collection<Long> userCardIds, YearMonth month) {
        Map<Long, RecentChange> recent = new HashMap<>();
        if (userCardIds.isEmpty()) {
            return recent;
        }
        jdbc.query("""
                select user_card_id, amount, approved_at, merchant_name from (
                    select p.user_card_id, p.recognized_performance_amount as amount, p.approved_at,
                           coalesce(nullif(p.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)
                               as merchant_name,
                           row_number() over (partition by p.user_card_id
                                              order by p.approved_at desc, p.payment_transaction_id desc) as rn
                    from payment_transaction p
                    join merchant m on m.merchant_id = p.merchant_id
                    where p.user_id = :userId and p.user_card_id in (:ids)
                      and p.status = 'APPROVED' and p.performance_month = :month
                      and p.recognized_performance_amount > 0
                ) latest
                where rn = 1
                """, params(userId, userCardIds, month), rs -> {
                    recent.put(rs.getLong("user_card_id"), new RecentChange(rs.getLong("amount"),
                            rs.getObject("approved_at", LocalDateTime.class), rs.getString("merchant_name")));
                });
        return recent;
    }

    private static MapSqlParameterSource params(long userId, Collection<Long> ids, YearMonth month) {
        return new MapSqlParameterSource("userId", userId).addValue("ids", ids)
                .addValue("month", Date.valueOf(month.atDay(1)));
    }
}
