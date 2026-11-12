package com.yamu.be.performance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 예정 지출을 "이후 전부" 카드 옮기기로 바꾸면 사라지거나 거절되는 것이 있는 항목을 찾는다(S15P31E103-375, 2026-11-12 결정).
 *
 * <p>앱의 옮기기는 {@code PATCH /api/cashflow-items/{id} {userCardId}}(범위 생략 = THIS_AND_FUTURE)다. 이 수정은 오늘 이후 첫
 * 회차부터 규칙 값에 카드만 바꿔 덮어쓰고, 그 뒤에 따로 고친 회차·지운 회차 기록과 뒤에 이어진 규칙을 지운다. 결제와 연결된 회차가
 * 있으면 409로 거절한다. 그래서 아래 항목은 분석이 보여 준 금액과 옮긴 뒤 결과가 다르거나 옮기기가 실패하므로 옮길 수 없음으로 둔다.
 *
 * <ul>
 *   <li>{@link ScheduledExpenses.MoveLock#MATCHED}: 원래 회차나 예정일이 오늘 이후인 회차 중 결제와 연결된 것이 있음.</li>
 *   <li>{@link ScheduledExpenses.MoveLock#EDITED}: 원래 회차나 예정일이 오늘 이후인 회차 중 이번 회차만 고쳤거나(is_overridden)
 *       지운(EXCLUDED) 것이 있음, 또는 뒤에 이어진 규칙(나중 회차부터 바꿔 둔 값)이 있음. 이번 달 밖의 회차도 옮기기가 지우므로 센다.</li>
 * </ul>
 *
 * <p>가계부 코드는 고치지 않고 표만 읽는다. 모든 쿼리에 {@code user_id}를 넣어 남의 항목을 읽지 않는다.
 */
@Component
class MoveLockReader {

    private final NamedParameterJdbcTemplate jdbc;

    MoveLockReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param itemIds 예정 지출 규칙 번호(목록의 cashflowItemId)
     * @param today   한국 날짜 오늘
     * @return 잠긴 항목만. 둘 다 걸리면 MATCHED
     */
    Map<Long, ScheduledExpenses.MoveLock> locks(long userId, Collection<Long> itemIds, LocalDate today) {
        Map<Long, ScheduledExpenses.MoveLock> locks = new HashMap<>();
        if (itemIds.isEmpty()) {
            return locks;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("ids", itemIds).addValue("today", today);
        jdbc.query("""
                select ci.cashflow_item_id,
                       exists (select 1 from cashflow_occurrence o
                               join cashflow_payment_match m on m.cashflow_occurrence_id = o.cashflow_occurrence_id
                               where o.cashflow_item_id = ci.cashflow_item_id and o.user_id = :userId
                                 and (o.occurrence_date >= :today or o.scheduled_date >= :today)) as matched,
                       (exists (select 1 from cashflow_occurrence o
                                where o.cashflow_item_id = ci.cashflow_item_id and o.user_id = :userId
                                  and (o.occurrence_date >= :today or o.scheduled_date >= :today)
                                  and (o.is_overridden = true or o.status = 'EXCLUDED'))
                        or exists (select 1 from cashflow_item n
                                   where n.previous_cashflow_item_id = ci.cashflow_item_id and n.user_id = :userId))
                           as edited
                from cashflow_item ci
                where ci.user_id = :userId and ci.cashflow_item_id in (:ids)
                """, params, rs -> {
                    if (rs.getBoolean(2)) {
                        locks.put(rs.getLong(1), ScheduledExpenses.MoveLock.MATCHED);
                    } else if (rs.getBoolean(3)) {
                        locks.put(rs.getLong(1), ScheduledExpenses.MoveLock.EDITED);
                    }
                });
        return locks;
    }
}
