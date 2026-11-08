package com.yamu.be.payment.billing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 청구 확정 때 저장한 회차별 청구할인 배분({@code bill_item_discount}, Task 5.4.7)을 읽는다. */
@Component
public class BillItemDiscountReader {

    private final NamedParameterJdbcTemplate jdbc;

    public BillItemDiscountReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 청구서의 회차 번호 → 그 회차에 나눈 할인 합계. 할인이 없는 회차는 없다. */
    public Map<Long, Long> amountsByItem(Long cardBillId) {
        return jdbc.query("""
                SELECT bill_item_id, SUM(amount) AS amount FROM bill_item_discount
                 WHERE card_bill_id = :bill GROUP BY bill_item_id
                """, new MapSqlParameterSource("bill", cardBillId),
                (rs, n) -> Map.entry(rs.getLong("bill_item_id"), rs.getLong("amount")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 청구서 상세의 {@code discounts}: 혜택(payment_benefit)마다 이 청구서에 나눈 금액. 확정 시각은 청구서의 확정 시각이다.
     * 사용자 번호로 거른다(남의 청구서는 읽지 않는다).
     */
    public List<Line> lines(Long userId, Long cardBillId) {
        return jdbc.query("""
                SELECT d.payment_benefit_id, pb.payment_transaction_id, cb.benefit_name, SUM(d.amount) AS amount,
                       b.confirmed_at
                  FROM bill_item_discount d
                  JOIN card_bill b ON b.card_bill_id = d.card_bill_id
                  JOIN payment_benefit pb ON pb.payment_benefit_id = d.payment_benefit_id
                  JOIN card_benefit cb ON cb.card_benefit_id = pb.card_benefit_id
                 WHERE d.card_bill_id = :bill AND b.user_id = :user
                 GROUP BY d.payment_benefit_id, pb.payment_transaction_id, cb.benefit_name, b.confirmed_at
                 ORDER BY d.payment_benefit_id
                """, new MapSqlParameterSource("bill", cardBillId).addValue("user", userId),
                (rs, n) -> new Line(rs.getLong("payment_benefit_id"), rs.getLong("payment_transaction_id"),
                        rs.getString("benefit_name"), rs.getLong("amount"),
                        rs.getObject("confirmed_at", LocalDateTime.class)));
    }

    public record Line(Long paymentBenefitId, Long paymentId, String name, long amount, LocalDateTime confirmedAt) {}
}
