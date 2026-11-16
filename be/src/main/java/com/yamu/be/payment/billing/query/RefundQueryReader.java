package com.yamu.be.payment.billing.query;

import com.yamu.be.payment.billing.RefundStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 환급 내역(Task 5.4.8)이 읽는 행. 신용카드 환급({@code refund})과 취소된 체크카드 결제를 하나로 합쳐(UNION ALL) 생성 시각 순으로 읽는다.
 * 모든 조회는 사용자 번호를 조건에 넣어 남의 환급을 읽지 않는다. 쿼리 수는 고정이다(목록 1 + 개수 1 + 합계 1).
 */
@Repository
class RefundQueryReader {

    static final String CANCEL_AFTER_CONFIRM = "CANCEL_AFTER_CONFIRM";
    static final String CHECK_CANCEL = "CHECK_CANCEL";

    private static final String MERCHANT_NAME =
            "coalesce(nullif(t.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)";

    /**
     * 신용카드 환급 + 체크카드 취소 환불. 체크카드는 0원 승인(출금 없음)과 등록 전 거래(SEED, 취소 불가)를 뺀다.
     * 무효(CANCELED) 환급은 돌려줄 돈이 없으므로 금액을 0으로 읽는다.
     */
    private static final String UNION = """
            select r.refund_id, r.payment_transaction_id, r.bill_item_id, i.card_bill_id, t.user_card_id,
                   c.card_alias, p.product_name, %1$s as merchant_name, 'CANCEL_AFTER_CONFIRM' as reason,
                   case when r.status = 'CANCELED' then 0 else r.refund_amount end as amount, r.status, r.created_at,
                   case when r.due_date is null and b.status = 'CONFIRMED' then b.withdrawal_date end as scheduled_after,
                   r.due_date, r.completed_at, r.fin_transaction_no,
                   a.linked_account_id, a.bank_code, a.account_last4
              from refund r
              join bill_item i on i.bill_item_id = r.bill_item_id and i.user_id = r.user_id
              left join card_bill b on b.card_bill_id = i.card_bill_id and b.user_id = r.user_id
              join payment_transaction t on t.payment_transaction_id = r.payment_transaction_id and t.user_id = r.user_id
              join merchant m on m.merchant_id = t.merchant_id
              left join user_card c on c.user_card_id = t.user_card_id
              left join card_product p on p.card_product_id = t.card_product_id
              left join linked_account a on a.linked_account_id = r.linked_account_id and a.user_id = r.user_id
             where r.user_id = :userId
            union all
            select null, t.payment_transaction_id, null, null, t.user_card_id,
                   c.card_alias, p.product_name, %1$s, 'CHECK_CANCEL',
                   t.approved_amount, 'COMPLETED', t.canceled_at,
                   null, null, t.canceled_at, null,
                   a.linked_account_id, a.bank_code, a.account_last4
              from payment_transaction t
              join card_product p on p.card_product_id = t.card_product_id and p.card_type = 'CHECK'
              join merchant m on m.merchant_id = t.merchant_id
              left join user_card c on c.user_card_id = t.user_card_id
              left join linked_account a on a.linked_account_id = t.refund_linked_account_id and a.user_id = t.user_id
             where t.user_id = :userId and t.status = 'CANCELED' and t.source = 'YAMU' and t.approved_amount > 0
            """.formatted(MERCHANT_NAME);

    private static final String FILTER = """
             where (:status is null or x.status = :status)
               and x.created_at >= :from and x.created_at < :until
            """;

    private final NamedParameterJdbcTemplate jdbc;

    RefundQueryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param from  생성 시각 하한(UTC, 포함)
     * @param until 생성 시각 상한(UTC, 미포함)
     */
    record Filter(long userId, RefundStatus status, LocalDateTime from, LocalDateTime until) {

        MapSqlParameterSource params() {
            return new MapSqlParameterSource("userId", userId)
                    .addValue("status", status == null ? null : status.name())
                    .addValue("from", from)
                    .addValue("until", until);
        }
    }

    /** 최근 생성 순. 같은 시각이면 결제·환급 번호가 큰 쪽이 앞이라 페이지가 바뀌어도 순서가 흔들리지 않는다. */
    List<Row> page(Filter filter, long offset, int limit) {
        return jdbc.query("select x.* from (" + UNION + ") x" + FILTER + """
                 order by x.created_at desc, x.payment_transaction_id desc, x.refund_id desc
                 limit :limit offset :offset
                """, filter.params().addValue("limit", limit).addValue("offset", offset), (rs, n) -> new Row(
                rs.getObject("refund_id", Long.class), rs.getLong("payment_transaction_id"),
                rs.getObject("bill_item_id", Long.class), rs.getObject("card_bill_id", Long.class),
                rs.getObject("user_card_id", Long.class), rs.getString("card_alias"), rs.getString("product_name"),
                rs.getString("merchant_name"), rs.getString("reason"), rs.getLong("amount"),
                RefundStatus.valueOf(rs.getString("status")), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("scheduled_after", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getObject("completed_at", LocalDateTime.class), rs.getString("fin_transaction_no"),
                rs.getObject("linked_account_id", Long.class), rs.getString("bank_code"),
                rs.getString("account_last4")));
    }

    long count(Filter filter) {
        Long count = jdbc.queryForObject("select count(*) from (" + UNION + ") x" + FILTER, filter.params(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 입금 전(PENDING) 신용카드 환급의 금액·건수. 기간과 상관없다.
     * 기한({@code due_date})이 아직 없는 환급은 원 청구서의 출금을 기다리는 중이라 금액이 예상값이다(그 몫을 따로 센다).
     */
    Pending pending(long userId) {
        return jdbc.queryForObject("""
                select coalesce(sum(refund_amount), 0) as amount, count(*) as cnt,
                       coalesce(sum(case when due_date is null then refund_amount else 0 end), 0) as estimated_amount,
                       coalesce(sum(case when due_date is null then 1 else 0 end), 0) as estimated_cnt
                  from refund where user_id = :userId and status = 'PENDING'
                """, new MapSqlParameterSource("userId", userId), (rs, n) -> new Pending(rs.getLong("amount"),
                rs.getLong("cnt"), rs.getLong("estimated_amount"), rs.getLong("estimated_cnt")));
    }

    /** @param estimatedAmount 전체 PENDING 금액({@code amount}) 가운데 기한이 아직 없어 예상값인 몫 */
    record Pending(long amount, long count, long estimatedAmount, long estimatedCount) {}

    record Row(Long refundId, long paymentId, Long billItemId, Long billId, Long userCardId, String cardAlias,
            String productName, String merchantName, String reason, long amount, RefundStatus status,
            LocalDateTime createdAt, LocalDate scheduledAfter, LocalDate dueDate, LocalDateTime completedAt,
            String finTransactionNo, Long accountId, String bankCode, String accountLast4) {}
}
