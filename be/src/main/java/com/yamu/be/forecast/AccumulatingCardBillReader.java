package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 임시 청구 원천. BE 2 {@code CardBillReader}(S15P31E103-205)가 머지되면 지우고 그 구현으로 바꾼다(2026-11-04 BE 2 합의).
 *
 * <p>card_bill을 읽기만 한다. 청구 확정·출금 배치(-206·-207)가 아직 없어 지금 생기는 청구는 이용기간 진행 중(ACCUMULATING)뿐이라
 * 그것만 읽는다. 상태별 계약({@link CardBillReader})의 ACCUMULATING 규칙을 따른다.
 * <ul>
 *   <li>금액: 그 청구서에 붙은 청구 전(PENDING) 회차 원금 합계. 예상 청구할인은 계산할 원천이 없어 빼지 않는다(출금을 크게 잡는 쪽).
 *       확정 전 취소로 제외된 회차(EXCLUDED)는 넣지 않는다. 회차가 없는 빈 청구서(승인 저장 실패로 남은 것)는 넣지 않는다.</li>
 *   <li>계좌: 확정 전 청구서는 출금 계좌 칸이 비어 있어 카드의 현재 출금 계좌({@code user_card.linked_account_id})를 쓴다.</li>
 *   <li>날짜: 청구서의 출금 예정일. 신용 승인이 이미 비영업일 보정을 했다.</li>
 * </ul>
 */
@Component
class AccumulatingCardBillReader implements CardBillReader {

    private final NamedParameterJdbcTemplate jdbc;

    AccumulatingCardBillReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutstandingBill> outstanding(Long userId, List<Long> accountIds, LocalDate to) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT b.card_bill_id, c.linked_account_id, b.withdrawal_date, SUM(i.principal_amount) AS amount
                  FROM card_bill b
                  JOIN user_card c ON c.user_card_id = b.user_card_id AND c.user_id = b.user_id
                  JOIN bill_item i ON i.card_bill_id = b.card_bill_id AND i.status = 'PENDING'
                 WHERE b.user_id = :userId
                   AND b.status = 'ACCUMULATING'
                   AND c.linked_account_id IN (:accountIds)
                   AND b.withdrawal_date <= :to
                 GROUP BY b.card_bill_id, c.linked_account_id, b.withdrawal_date
                HAVING SUM(i.principal_amount) > 0
                 ORDER BY b.withdrawal_date, b.card_bill_id
                """,
                new MapSqlParameterSource("userId", userId).addValue("accountIds", accountIds).addValue("to", to),
                (rs, rowNum) -> new OutstandingBill(rs.getLong("card_bill_id"), rs.getLong("linked_account_id"),
                        Status.ACCUMULATING, rs.getObject("withdrawal_date", LocalDate.class), rs.getLong("amount")));
    }
}
