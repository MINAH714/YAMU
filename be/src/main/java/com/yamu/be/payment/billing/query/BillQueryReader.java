package com.yamu.be.payment.billing.query;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.RefundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 청구서 조회(노션 API 명세 V2 「청구서 목록」·「청구서 상세」)가 읽는 행. 모든 조회는 사용자 번호를 조건에 넣어 남의 청구서를 읽지 않는다.
 *
 * <p>출금 계좌: 진행 중(ACCUMULATING) 청구서는 확정 전이라 청구서의 계좌 칸이 비어 있어 카드의 지금 출금 계좌를 쓰고,
 * 확정 뒤에는 확정 당시 계좌({@code card_bill.linked_account_id})를 쓴다. 확정 뒤 카드의 계좌를 바꿔도 이미 확정된 청구서는 옛 계좌에서 출금된다.
 */
@Repository
class BillQueryReader {

    /** 청구서 한 줄 + 카드·계좌 표시값 + 회차 합계. 확정 전 취소로 빠진 회차(EXCLUDED)는 건수·합계에 넣지 않는다. */
    private static final String BILL_SELECT = """
            SELECT b.card_bill_id, b.user_card_id, c.card_alias, c.card_last4, p.product_name, p.billing_cutoff_days,
                   b.billing_month, b.period_start, b.period_end, b.withdrawal_date, b.status,
                   b.confirmed_amount, b.paid_amount, b.canceled_unpaid_principal,
                   b.created_at, b.confirmed_at, b.paid_at, b.failed_at, b.fin_transaction_no,
                   a.linked_account_id AS account_id, a.bank_code, a.account_last4,
                   COALESCE(s.item_count, 0) AS item_count, COALESCE(s.item_amount, 0) AS item_amount,
                   COALESCE(s.pending_amount, 0) AS pending_amount
              FROM card_bill b
              JOIN user_card c ON c.user_card_id = b.user_card_id AND c.user_id = b.user_id
              JOIN card_product p ON p.card_product_id = c.card_product_id
              LEFT JOIN linked_account a ON a.user_id = b.user_id
                   AND a.linked_account_id = CASE WHEN b.status = 'ACCUMULATING' THEN c.linked_account_id
                                                  ELSE b.linked_account_id END
              LEFT JOIN (SELECT i.card_bill_id,
                                SUM(CASE WHEN i.status <> 'EXCLUDED' THEN 1 ELSE 0 END) AS item_count,
                                SUM(CASE WHEN i.status <> 'EXCLUDED'
                                         THEN COALESCE(i.billed_amount, i.principal_amount) ELSE 0 END) AS item_amount,
                                SUM(CASE WHEN i.status = 'PENDING' THEN i.principal_amount ELSE 0 END) AS pending_amount
                           FROM bill_item i
                          WHERE i.user_id = :userId
                          GROUP BY i.card_bill_id) s ON s.card_bill_id = b.card_bill_id
             WHERE b.user_id = :userId
            """;

    private static final String ORDER = " ORDER BY b.billing_month DESC, b.card_bill_id DESC";

    private final NamedParameterJdbcTemplate jdbc;

    BillQueryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 본인 카드의 종류. 없는 카드·남의 카드는 비어 있다(존재 여부를 드러내지 않게 같은 404로 처리). */
    Optional<CardType> cardType(long userId, long userCardId) {
        return jdbc.query("""
                SELECT p.card_type
                  FROM user_card c
                  JOIN card_product p ON p.card_product_id = c.card_product_id
                 WHERE c.user_card_id = :userCardId AND c.user_id = :userId
                """, new MapSqlParameterSource("userId", userId).addValue("userCardId", userCardId),
                (rs, rowNum) -> CardType.valueOf(rs.getString("card_type"))).stream().findFirst();
    }

    /**
     * 목록. month가 있으면 그 청구 월만, 없으면 끝나지 않은 청구서(진행 중·확정·미납) 전부와 recentFrom 이후 청구 월의 청구서.
     *
     * @param userCardId null이면 모든 카드
     * @param status     null이면 모든 상태
     */
    List<BillRow> bills(long userId, Long userCardId, BillStatus status, YearMonth month, LocalDate recentFrom) {
        StringBuilder sql = new StringBuilder(BILL_SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        if (userCardId != null) {
            sql.append(" AND b.user_card_id = :userCardId");
            params.addValue("userCardId", userCardId);
        }
        if (status != null) {
            sql.append(" AND b.status = :status");
            params.addValue("status", status.name());
        }
        if (month != null) {
            sql.append(" AND b.billing_month = :month");
            params.addValue("month", month.atDay(1));
        } else {
            sql.append(" AND (b.status IN ('ACCUMULATING', 'CONFIRMED', 'UNPAID') OR b.billing_month >= :recentFrom)");
            params.addValue("recentFrom", recentFrom);
        }
        return jdbc.query(sql.append(ORDER).toString(), params, BillQueryReader::bill);
    }

    /** 요약(다음 출금·미납 건수)에 쓰는 끝나지 않은 청구서. 목록의 상태·월 필터와 상관없이 카드 필터만 따른다. */
    List<BillRow> openBills(long userId, Long userCardId) {
        StringBuilder sql = new StringBuilder(BILL_SELECT)
                .append(" AND b.status IN ('ACCUMULATING', 'CONFIRMED', 'UNPAID')");
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        if (userCardId != null) {
            sql.append(" AND b.user_card_id = :userCardId");
            params.addValue("userCardId", userCardId);
        }
        return jdbc.query(sql.append(ORDER).toString(), params, BillQueryReader::bill);
    }

    /** 본인 청구서 하나. 없는 청구서·남의 청구서는 비어 있다. */
    Optional<BillRow> bill(long userId, long billId) {
        return jdbc.query(BILL_SELECT + " AND b.card_bill_id = :billId",
                new MapSqlParameterSource("userId", userId).addValue("billId", billId), BillQueryReader::bill)
                .stream().findFirst();
    }

    /**
     * 청구서의 회차. 확정 전 취소로 빠진 회차(EXCLUDED)도 보여 준다(금액 합계에는 넣지 않음).
     * 가맹점 이름은 승인 당시 기록(merchant_context_snapshot)을 먼저 쓰고, 없으면 가맹점 표의 이름을 쓴다.
     */
    List<ItemRow> items(long userId, long billId) {
        return jdbc.query("""
                SELECT i.bill_item_id, i.payment_transaction_id, t.approved_at,
                       COALESCE(NULLIF(t.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)
                           AS merchant_name,
                       t.installment_months, i.installment_no, i.principal_amount, i.fee_amount, i.billed_amount,
                       i.status
                  FROM bill_item i
                  JOIN payment_transaction t ON t.payment_transaction_id = i.payment_transaction_id
                       AND t.user_id = i.user_id
                  LEFT JOIN merchant m ON m.merchant_id = t.merchant_id
                 WHERE i.card_bill_id = :billId AND i.user_id = :userId
                 ORDER BY t.approved_at DESC, i.bill_item_id DESC
                """, new MapSqlParameterSource("userId", userId).addValue("billId", billId), (rs, rowNum) -> new ItemRow(
                rs.getLong("bill_item_id"), rs.getLong("payment_transaction_id"),
                rs.getObject("approved_at", LocalDateTime.class), rs.getString("merchant_name"),
                rs.getObject("installment_months", Integer.class), rs.getInt("installment_no"),
                rs.getLong("principal_amount"), rs.getObject("fee_amount", Long.class),
                rs.getObject("billed_amount", Long.class), BillItemStatus.valueOf(rs.getString("status"))));
    }

    /** 청구서 회차의 환급(신용카드 취소, POL-006). */
    List<RefundRow> refunds(long userId, long billId) {
        return jdbc.query("""
                SELECT r.refund_id, r.bill_item_id, r.payment_transaction_id, r.refund_amount, r.status, r.completed_at
                  FROM refund r
                  JOIN bill_item i ON i.bill_item_id = r.bill_item_id AND i.user_id = r.user_id
                 WHERE i.card_bill_id = :billId AND r.user_id = :userId
                 ORDER BY r.refund_id
                """, new MapSqlParameterSource("userId", userId).addValue("billId", billId), (rs, rowNum) -> new RefundRow(
                rs.getLong("refund_id"), rs.getLong("bill_item_id"), rs.getLong("payment_transaction_id"),
                rs.getLong("refund_amount"), RefundStatus.valueOf(rs.getString("status")),
                rs.getObject("completed_at", LocalDateTime.class)));
    }

    private static BillRow bill(ResultSet rs, int rowNum) throws SQLException {
        return new BillRow(rs.getLong("card_bill_id"), rs.getLong("user_card_id"), rs.getString("card_alias"),
                rs.getString("card_last4"), rs.getString("product_name"), rs.getObject("billing_cutoff_days", Integer.class),
                rs.getObject("billing_month", LocalDate.class), rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class), rs.getObject("withdrawal_date", LocalDate.class),
                BillStatus.valueOf(rs.getString("status")), rs.getObject("confirmed_amount", Long.class),
                rs.getLong("paid_amount"), rs.getLong("canceled_unpaid_principal"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("confirmed_at", LocalDateTime.class),
                rs.getObject("paid_at", LocalDateTime.class), rs.getObject("failed_at", LocalDateTime.class),
                rs.getString("fin_transaction_no"), rs.getObject("account_id", Long.class), rs.getString("bank_code"),
                rs.getString("account_last4"), rs.getInt("item_count"), rs.getLong("item_amount"),
                rs.getLong("pending_amount"));
    }

    /**
     * @param accountId     출금 계좌(진행 중이면 카드의 지금 계좌, 확정 뒤에는 확정 당시 계좌). 계좌가 없으면 null
     * @param itemCount     청구에서 빠진 회차를 뺀 회차 수
     * @param itemAmount    청구에서 빠진 회차를 뺀 회차 청구액(원금 + 수수료) 합계
     * @param pendingAmount 청구 전(PENDING) 회차 원금 합계. 진행 중 청구서의 예상 청구액 바탕
     */
    record BillRow(long billId, long userCardId, String cardAlias, String cardLast4, String productName,
            Integer cutoffDays, LocalDate billingMonth, LocalDate periodStart, LocalDate periodEnd,
            LocalDate withdrawalDate, BillStatus status, Long confirmedAmount, long paidAmount,
            long canceledUnpaidPrincipal, LocalDateTime createdAt, LocalDateTime confirmedAt, LocalDateTime paidAt,
            LocalDateTime failedAt, String finTransactionNo, Long accountId, String bankCode, String accountLast4,
            int itemCount, long itemAmount, long pendingAmount) {}

    /** @param installmentMonths 원결제의 개월 수(일시불 1) */
    record ItemRow(long billItemId, long paymentId, LocalDateTime approvedAt, String merchantName,
            Integer installmentMonths, int installmentNo, long principalAmount, Long feeAmount, Long billedAmount,
            BillItemStatus status) {}

    record RefundRow(long refundId, long billItemId, long paymentId, long amount, RefundStatus status,
            LocalDateTime completedAt) {}
}
