package com.yamu.be.payment.billing;

import com.yamu.be.forecast.CardBillReader;
import com.yamu.be.payment.billing.confirm.BillingDiscountSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 잔고예보가 읽는 아직 출금되지 않은 신용카드 청구(Task 5.4.1). 계약은 {@link CardBillReader}(2026-10-30 MR !45에서 BE 4와 합의)를 따른다.
 *
 * <ul>
 *   <li>진행 중(ACCUMULATING): 금액은 청구 전(PENDING) 회차 원금 합계 − 예상 청구할인, 계좌는 카드의 지금 출금 계좌.
 *       확정 전 취소로 빠진 회차(EXCLUDED)는 넣지 않는다. 예상 청구할인은 청구 확정과 같은 배분({@link BillingDiscountSource})으로 계산한다(Task 5.4.7).</li>
 *   <li>확정(CONFIRMED): 금액은 확정 원본 청구액, 계좌는 확정 당시 계좌. 확정 뒤 취소는 확정액을 바꾸지 않고 환급으로 처리하므로(POL-006)
 *       확정액이 그대로 빠진다. 출금일이 지났어도 아직 출금 결과가 없으면 넣는다.</li>
 *   <li>미납(UNPAID): 남은 미납액(확정액 − 출금된 금액 − 취소로 뺀 미납 원금), 확정 당시 계좌, 원래 출금일. 출금일과 상관없이 넣는다.</li>
 *   <li>출금 완료(PAID)와 미납이 취소로 0원이 된 CANCELED는 넣지 않는다.</li>
 * </ul>
 * 금액이 0원 이하인 청구(회차가 없는 빈 청구서 등)는 빠질 돈이 없어 넣지 않는다. 고른 계좌가 아닌 청구와 남의 청구는 넣지 않는다.
 *
 * <p>출금 계좌가 없는 청구는 빼지 않고 계좌를 비운 채 넘긴다. 계약대로 잔고예보가 데이터 계약 위반으로 실패한다(조용히 빼면 출금이
 * 예보에서 사라져 잔액을 높게 보인다). 실제로는 생기지 않는다: 확정 뒤 청구서는 {@code ck_bill_confirmed}로 계좌가 있고, 진행 중
 * 청구서의 카드는 등록할 때 계좌가 정해지고(없으면 등록 실패) 계좌를 비우는 곳이 없으며, 계좌 없는 카드는 결제 승인에서 거절해 회차가
 * 생기지 않는다.
 */
@Slf4j
@Component
class OutstandingCardBillReader implements CardBillReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final BillingDiscountSource discounts;

    OutstandingCardBillReader(NamedParameterJdbcTemplate jdbc, BillingDiscountSource discounts) {
        this.jdbc = jdbc;
        this.discounts = discounts;
    }

    @Override
    public List<OutstandingBill> outstanding(Long userId, List<Long> accountIds, LocalDate to) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        List<Row> rows = jdbc.query("""
                SELECT x.* FROM (
                    SELECT b.card_bill_id, b.status, b.withdrawal_date,
                           CASE WHEN b.status = 'ACCUMULATING' THEN c.linked_account_id ELSE b.linked_account_id END
                               AS account_id,
                           b.confirmed_amount, b.paid_amount, b.canceled_unpaid_principal,
                           (SELECT COALESCE(SUM(i.principal_amount), 0)
                              FROM bill_item i
                             WHERE i.card_bill_id = b.card_bill_id AND i.user_id = b.user_id AND i.status = 'PENDING')
                               AS pending_amount
                      FROM card_bill b
                      JOIN user_card c ON c.user_card_id = b.user_card_id AND c.user_id = b.user_id
                     WHERE b.user_id = :userId
                       AND ((b.status IN ('ACCUMULATING', 'CONFIRMED') AND b.withdrawal_date <= :to)
                            OR b.status = 'UNPAID')
                ) x
                 WHERE x.account_id IN (:accountIds) OR x.account_id IS NULL
                 ORDER BY x.withdrawal_date, x.card_bill_id
                """,
                new MapSqlParameterSource("userId", userId).addValue("accountIds", accountIds).addValue("to", to),
                (rs, rowNum) -> new Row(rs.getLong("card_bill_id"), Status.valueOf(rs.getString("status")),
                        rs.getObject("account_id", Long.class), rs.getObject("withdrawal_date", LocalDate.class),
                        rs.getObject("confirmed_amount", Long.class), rs.getLong("paid_amount"),
                        rs.getLong("canceled_unpaid_principal"), rs.getLong("pending_amount")));
        // 진행 중 청구서의 예상 청구할인은 행을 다 읽은 뒤 한 번에 계산한다(청구서 수와 상관없이 쿼리 3번, !141 리뷰)
        List<Long> accumulating = rows.stream().filter(row -> row.status() == Status.ACCUMULATING).map(Row::billId).toList();
        Map<Long, List<BillingDiscountSource.Allocation>> expected = accumulating.isEmpty() ? Map.of()
                : discounts.allocateAll(accumulating);
        return rows.stream().map(row -> {
            long amount = switch (row.status()) {
                case ACCUMULATING -> row.pendingAmount()
                        - BillingDiscountSource.Allocation.total(expected.getOrDefault(row.billId(), List.of()));
                case CONFIRMED -> confirmedOrZero(row.billId(), row.confirmed());
                case UNPAID -> confirmedOrZero(row.billId(), row.confirmed()) - row.paid() - row.canceledUnpaid();
            };
            return amount <= 0 ? null
                    : new OutstandingBill(row.billId(), row.accountId(), row.status(), row.withdrawalDate(), amount);
        }).filter(Objects::nonNull).toList();
    }

    /** 쿼리 한 행. 금액 계산은 예상 청구할인을 모은 뒤에 한다. */
    private record Row(long billId, Status status, Long accountId, LocalDate withdrawalDate, Long confirmed, long paid,
            long canceledUnpaid, long pendingAmount) {}

    /** 확정·미납 청구서에는 확정액이 있어야 한다(ck_bill_confirmed는 확정 시각이 있을 때만 막는다). 없으면 예보에서 빼고 남긴다. */
    private static long confirmedOrZero(long billId, Long confirmed) {
        if (confirmed == null) {
            log.warn("확정액이 없는 확정·미납 청구서라 잔고예보에서 뺍니다. cardBillId={}", billId);
            return 0;
        }
        return confirmed;
    }
}
