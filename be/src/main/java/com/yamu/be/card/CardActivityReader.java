package com.yamu.be.card;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 카드 관리 화면에 같이 보여 줄 청구·실적을 읽는다. 청구(card_bill)는 BE 2, 실적(card_monthly_performance)은
 * 실적 담당이 쓰는 테이블이라 BE 1은 SQL로 읽기만 한다.
 *
 * <p>청구 상태 값은 API 명세의 이름(CONFIRMED 확정·출금 전, UNPAID 미납, PAID 출금 완료, CANCELED 취소)을 쓴다.
 * card_bill.status에는 허용값 목록이 없어(DDL·사전 모두 "상태 코드") 청구를 만드는 BE 2 코드가 생기면 이름을 맞춘다.
 */
@Component
@RequiredArgsConstructor
class CardActivityReader {

    /**
     * 끝난 청구. 이 상태가 아닌 청구가 하나라도 있으면 카드를 해제할 수 없다(명세 CARD_HAS_OPEN_BILL).
     * 막을 상태를 나열하지 않고 끝난 상태만 나열한다. 출금 진행 중·실패처럼 나중에 생길 상태도 기본으로 막기 위해서다.
     */
    private static final List<String> SETTLED_BILL_STATUSES = List.of("PAID", "CANCELED");

    private final NamedParameterJdbcTemplate jdbc;

    /** 카드별 한 달 실적(한국 기준 월). 집계된 행이 없는 카드는 빠진다. */
    Map<Long, Long> recognizedAmounts(Collection<Long> userCardIds, YearMonth month) {
        Map<Long, Long> amounts = new HashMap<>();
        if (userCardIds.isEmpty()) {
            return amounts;
        }
        jdbc.query("""
                SELECT user_card_id, recognized_amount FROM card_monthly_performance
                WHERE user_card_id IN (:ids) AND performance_month = :month
                """, new MapSqlParameterSource("ids", userCardIds).addValue("month", Date.valueOf(month.atDay(1))),
                rs -> {
                    amounts.put(rs.getLong(1), rs.getLong(2));
                });
        return amounts;
    }

    /** 미납 청구가 있는 카드. */
    Set<Long> cardsWithUnpaidBill(Collection<Long> userCardIds) {
        Set<Long> cards = new HashSet<>();
        if (userCardIds.isEmpty()) {
            return cards;
        }
        jdbc.query("SELECT DISTINCT user_card_id FROM card_bill WHERE user_card_id IN (:ids) AND status = 'UNPAID'",
                new MapSqlParameterSource("ids", userCardIds), rs -> {
                    cards.add(rs.getLong(1));
                });
        return cards;
    }

    /** 끝나지 않은 청구서 번호(확정·미납·출금 중·실패 등 PAID·CANCELED가 아닌 것). */
    List<Long> unsettledBillIds(Long userCardId) {
        return jdbc.queryForList("""
                SELECT card_bill_id FROM card_bill
                WHERE user_card_id = :id AND status NOT IN (:statuses) ORDER BY card_bill_id
                """, new MapSqlParameterSource("id", userCardId).addValue("statuses", SETTLED_BILL_STATUSES), Long.class);
    }

    /** 출금일이 가장 가까운 확정 청구서. 없으면 empty. */
    Optional<NextWithdrawal> nextWithdrawal(Long userCardId) {
        return jdbc.query("""
                SELECT card_bill_id, confirmed_amount, withdrawal_date, status FROM card_bill
                WHERE user_card_id = :id AND status = 'CONFIRMED'
                ORDER BY withdrawal_date, card_bill_id LIMIT 1
                """, new MapSqlParameterSource("id", userCardId),
                (rs, row) -> new NextWithdrawal(rs.getLong(1), (Long) rs.getObject(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4))).stream().findFirst();
    }

    record NextWithdrawal(Long billId, Long amount, LocalDate withdrawalDate, String status) {
    }
}
