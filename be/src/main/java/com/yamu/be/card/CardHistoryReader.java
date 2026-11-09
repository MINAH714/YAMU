package com.yamu.be.card;

import com.yamu.be.card.dto.CardRegisterResponse.InitialPerformance;
import com.yamu.be.card.dto.CardRegisterResponse.Month;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 카드 등록 화면에 보여 줄 "등록 이전" 거래·실적을 읽는다. 결제·실적 테이블은 다른 파트가 쓰므로 BE 1은 SQL로 읽기만 한다.
 *
 * <p>시드 거래(payment_transaction.source = 'SEED')로 실적을 집계하는 일(FR-070)은 여기서 하지 않는다.
 * 집계된 card_monthly_performance가 있으면 그 값을, 없으면 NONE을 돌려준다.
 */
@Component
@RequiredArgsConstructor
class CardHistoryReader {

    private final JdbcTemplate jdbc;

    /** 등록 이전 모의 거래(시드)가 있는지. */
    boolean hasPriorHistory(Long userCardId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM payment_transaction WHERE user_card_id = ? AND source = 'SEED')",
                Boolean.class, userCardId);
        return Boolean.TRUE.equals(exists);
    }

    /** 전월·당월(한국 기준 월) 실적. 둘 다 없으면 NONE. */
    InitialPerformance initialPerformance(Long userCardId, LocalDate todayKst) {
        YearMonth current = YearMonth.from(todayKst);
        YearMonth previous = current.minusMonths(1);
        Map<YearMonth, Long> amounts = new HashMap<>();
        jdbc.query("""
                SELECT performance_month, recognized_amount FROM card_monthly_performance
                WHERE user_card_id = ? AND performance_month IN (?, ?)
                """, rs -> {
            amounts.put(YearMonth.from(rs.getObject(1, LocalDate.class)), rs.getLong(2));
        }, userCardId, Date.valueOf(previous.atDay(1)), Date.valueOf(current.atDay(1)));
        if (amounts.isEmpty()) {
            return InitialPerformance.none();
        }
        return new InitialPerformance("SEED", month(previous, amounts), month(current, amounts));
    }

    private static Month month(YearMonth month, Map<YearMonth, Long> amounts) {
        Long amount = amounts.get(month);
        return amount == null ? null : new Month(month.toString(), amount, null);
    }
}
