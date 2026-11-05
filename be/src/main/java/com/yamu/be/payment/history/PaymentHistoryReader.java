package com.yamu.be.payment.history;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제 내역을 SQL로 읽는다. 조회 전용이라 엔티티를 거치지 않는다.
 *
 * <p>청구 회차·청구서·혜택·한도·환급은 승인·청구 Task(5.2.x)가 쓰는 테이블이다. 엔티티를 공유하지 않고 Flyway 스키마의 칸만 읽으므로
 * 쓰는 쪽이 머지되면 응답이 그대로 채워진다. 결제를 읽는 조회(목록·합계·상세 한 건)는 {@code user_id}를 조건에 넣어 남의 결제를
 * 읽지 않는다. 하위 테이블 조회({@code find*(paymentId)})는 결제 번호로만 찾으므로, 반드시 {@link #findOne}으로 소유를 확인한
 * 결제 번호만 넘긴다.
 *
 * <p>쿼리 수는 고정이다. 목록은 페이지 1번 + 합계 1번, 상세는 결제 1번 + 하위 테이블마다 1번.
 */
@Component
class PaymentHistoryReader {

    private static final String LIST_COLUMNS = """
            select p.payment_transaction_id, p.source, p.status, p.requested_at, p.approved_at, p.canceled_at,
                   p.merchant_id,
                   coalesce(nullif(p.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name) as merchant_name,
                   coalesce(nullif(p.merchant_context_snapshot ->> '$.categoryCode', 'null'), m.merchant_category_code) as category_code,
                   p.payment_channel, p.requested_amount, p.approved_amount, p.installment_months,
                   p.recognized_performance_amount, p.performance_month, p.failure_code,
                   p.user_card_id, c.card_alias, c.card_last4, cp.product_name, cp.card_type,
                   (select cast(sum(b.krw_amount) as signed) from payment_benefit b
                    where b.payment_transaction_id = p.payment_transaction_id) as benefit_amount
            from payment_transaction p
            join merchant m on m.merchant_id = p.merchant_id
            left join user_card c on c.user_card_id = p.user_card_id
            left join card_product cp on cp.card_product_id = p.card_product_id
            """;

    private static final String FILTER = """
            where p.user_id = :userId
              and p.status in (:statuses)
              and (:from is null or p.requested_at >= :from)
              and (:until is null or p.requested_at < :until)
              and (:userCardId is null or p.user_card_id = :userCardId)
              and (:source is null or p.source = :source)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PaymentHistoryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 목록 조회 조건.
     *
     * @param from  요청 시각 하한(UTC, 포함). null이면 제한 없음
     * @param until 요청 시각 상한(UTC, 미포함). null이면 제한 없음
     */
    record Filter(Long userId, List<PaymentStatus> statuses, LocalDateTime from, LocalDateTime until, Long userCardId,
            TransactionSource source) {

        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("statuses", statuses.stream().map(Enum::name).toList())
                    .addValue("from", from)
                    .addValue("until", until)
                    .addValue("userCardId", userCardId)
                    .addValue("source", source == null ? null : source.name());
        }
    }

    /** 최근 요청 순. 같은 시각이면 결제 번호가 큰 쪽이 앞이라 페이지가 바뀌어도 순서가 흔들리지 않는다. */
    List<PaymentListResponse.Item> findPage(Filter filter, long offset, int limit) {
        MapSqlParameterSource params = filter.params().addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(LIST_COLUMNS + FILTER + """
                order by p.requested_at desc, p.payment_transaction_id desc
                limit :limit offset :offset
                """, params, (rs, i) -> item(rs));
    }

    /** 필터를 적용한 전체 개수와 승인 건의 금액·혜택 합계. */
    Totals totals(Filter filter) {
        return jdbc.queryForObject("""
                select count(*) as cnt,
                       coalesce(sum(case when p.status = 'APPROVED' then p.requested_amount end), 0) as amount,
                       coalesce(sum(case when p.status = 'APPROVED' then
                           (select sum(b.krw_amount) from payment_benefit b
                            where b.payment_transaction_id = p.payment_transaction_id) end), 0) as benefit
                from payment_transaction p
                """ + FILTER, filter.params(),
                (rs, i) -> new Totals(rs.getLong("cnt"), rs.getLong("amount"), rs.getLong("benefit")));
    }

    record Totals(long count, long amount, long benefit) {}

    /** 이 사용자의 결제 한 건. 없거나 남의 결제면 empty다. */
    Optional<PaymentRow> findOne(Long paymentId, Long userId) {
        List<PaymentRow> rows = jdbc.query(LIST_COLUMNS + """
                where p.payment_transaction_id = :paymentId and p.user_id = :userId
                """, new MapSqlParameterSource("paymentId", paymentId).addValue("userId", userId),
                (rs, i) -> row(rs));
        return rows.stream().findFirst();
    }

    /** 상세 화면에 쓰는 결제 행. 목록 칸에 상세에서만 쓰는 칸을 더했다. */
    record PaymentRow(PaymentListResponse.Item item, PaymentChannel channel, Long approvedAmount,
            Long recognizedPerformanceAmount, LocalDate performanceMonth, LocalDateTime canceledAt,
            String failureCode, String cardLast4) {}

    List<PaymentDetailResponse.Benefit> findBenefits(Long paymentId) {
        return jdbc.query("""
                select b.payment_benefit_id, b.card_benefit_id, cb.benefit_name, cb.benefit_type,
                       cb.application_timing, b.krw_amount
                from payment_benefit b
                join card_benefit cb on cb.card_benefit_id = b.card_benefit_id
                where b.payment_transaction_id = :paymentId
                order by b.payment_benefit_id
                """, new MapSqlParameterSource("paymentId", paymentId),
                (rs, i) -> new PaymentDetailResponse.Benefit(rs.getLong("payment_benefit_id"),
                        rs.getLong("card_benefit_id"), rs.getString("benefit_name"), rs.getString("benefit_type"),
                        null, rs.getObject("krw_amount", Long.class), rs.getString("application_timing"), null, null));
    }

    List<PaymentDetailResponse.LimitImpact> findLimitImpacts(Long paymentId) {
        return jdbc.query("""
                select u.limit_group_id, g.group_name, g.period_type, u.usage_month, u.consumed_value, g.limit_unit,
                       u.restored_at
                from payment_limit_usage u
                join limit_group g on g.limit_group_id = u.limit_group_id
                where u.payment_transaction_id = :paymentId
                order by u.payment_limit_usage_id
                """, new MapSqlParameterSource("paymentId", paymentId),
                (rs, i) -> new PaymentDetailResponse.LimitImpact(rs.getLong("limit_group_id"),
                        rs.getString("group_name"), rs.getString("period_type"),
                        yearMonth(rs.getObject("usage_month", LocalDate.class)), rs.getLong("consumed_value"),
                        rs.getString("limit_unit"), rs.getObject("restored_at", LocalDateTime.class)));
    }

    List<PaymentDetailResponse.BillItem> findBillItems(Long paymentId) {
        return jdbc.query("""
                select i.bill_item_id, i.installment_no, i.billed_amount, i.card_bill_id, i.billing_month,
                       b.withdrawal_date, i.status, i.principal_amount, i.fee_amount
                from bill_item i
                left join card_bill b on b.card_bill_id = i.card_bill_id
                where i.payment_transaction_id = :paymentId
                order by i.installment_no
                """, new MapSqlParameterSource("paymentId", paymentId),
                (rs, i) -> new PaymentDetailResponse.BillItem(rs.getLong("bill_item_id"), rs.getInt("installment_no"),
                        rs.getObject("billed_amount", Long.class), rs.getObject("card_bill_id", Long.class),
                        yearMonth(rs.getObject("billing_month", LocalDate.class)),
                        rs.getObject("withdrawal_date", LocalDate.class), rs.getString("status"),
                        rs.getLong("principal_amount"), rs.getObject("fee_amount", Long.class)));
    }

    List<PaymentDetailResponse.Refund> findRefunds(Long paymentId) {
        return jdbc.query("""
                select refund_id, bill_item_id, principal_amount, fee_amount, refund_amount, due_date, status,
                       completed_at
                from refund
                where payment_transaction_id = :paymentId
                order by refund_id
                """, new MapSqlParameterSource("paymentId", paymentId),
                (rs, i) -> new PaymentDetailResponse.Refund(rs.getLong("refund_id"), rs.getLong("bill_item_id"),
                        rs.getLong("principal_amount"), rs.getLong("fee_amount"), rs.getLong("refund_amount"),
                        rs.getObject("due_date", LocalDate.class), rs.getString("status"),
                        rs.getObject("completed_at", LocalDateTime.class)));
    }

    private static PaymentRow row(ResultSet rs) throws SQLException {
        return new PaymentRow(item(rs), PaymentChannel.valueOf(rs.getString("payment_channel")),
                rs.getObject("approved_amount", Long.class),
                rs.getObject("recognized_performance_amount", Long.class),
                rs.getObject("performance_month", LocalDate.class),
                rs.getObject("canceled_at", LocalDateTime.class),
                rs.getString("failure_code"), rs.getString("card_last4"));
    }

    private static PaymentListResponse.Item item(ResultSet rs) throws SQLException {
        PaymentStatus status = PaymentStatus.valueOf(rs.getString("status"));
        Integer months = rs.getObject("installment_months", Integer.class);
        boolean installment = months != null && months > 1;
        Long recognized = rs.getObject("recognized_performance_amount", Long.class);
        Long benefit = rs.getObject("benefit_amount", Long.class);
        return new PaymentListResponse.Item(
                rs.getLong("payment_transaction_id"),
                TransactionSource.valueOf(rs.getString("source")),
                status,
                rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("approved_at", LocalDateTime.class),
                new PaymentListResponse.Merchant(rs.getLong("merchant_id"), rs.getString("merchant_name"),
                        rs.getString("category_code"), null),
                rs.getLong("requested_amount"),
                installment ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM,
                installment ? months : null,
                card(rs),
                status != PaymentStatus.APPROVED || benefit == null ? 0 : benefit,
                null,
                status == PaymentStatus.APPROVED && recognized != null && recognized > 0);
    }

    private static PaymentListResponse.Card card(ResultSet rs) throws SQLException {
        Long userCardId = rs.getObject("user_card_id", Long.class);
        if (userCardId == null) {
            return null;
        }
        String alias = rs.getString("card_alias");
        return new PaymentListResponse.Card(userCardId, alias != null ? alias : rs.getString("product_name"),
                CardType.valueOf(rs.getString("card_type")));
    }

    private static String yearMonth(LocalDate firstDay) {
        return firstDay == null ? null : YearMonth.from(firstDay).toString();
    }
}
