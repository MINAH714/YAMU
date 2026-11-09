package com.yamu.be.payment.history;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.merchant.MerchantCategories;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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

    private static final String MERCHANT_NAME =
            "coalesce(nullif(p.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)";
    private static final String CATEGORY_CODE =
            "coalesce(nullif(p.merchant_context_snapshot ->> '$.categoryCode', 'null'), m.merchant_category_code)";
    /** 업종 코드 → 표시 이름(Task 297 업종 목록). 목록에 없는 코드는 이름이 없다 */
    private static final Map<String, String> CATEGORY_NAMES = MerchantCategories.list().stream()
            .collect(Collectors.toUnmodifiableMap(MerchantCategories.Category::merchantCategoryCode,
                    MerchantCategories.Category::merchantCategoryName));

    /**
     * 혜택 줄이 확정됐는지(2026-11-06 결정, {@link BenefitStatus}). 즉시할인은 승인 때, 청구할인은 결제의 회차가 모두 청구 확정되면
     * (청구 전 PENDING 회차가 없으면) 확정이다. 캐시백·적립·시점 미확인은 지급 기록이 없어 확정되지 않는다.
     * 임시: 할부 청구할인은 회차가 일부만 확정된 동안 확정으로 세지 않는다(회차별 배분은 S15P31E103-299의 bill_item_discount).
     */
    private static final String CONFIRMED_LINE = """
            (cb.application_timing = 'AT_APPROVAL'
             or (cb.application_timing = 'AT_BILLING'
                 and exists (select 1 from bill_item i where i.payment_transaction_id = b.payment_transaction_id)
                 and not exists (select 1 from bill_item i
                                 where i.payment_transaction_id = b.payment_transaction_id and i.status = 'PENDING')))
            """;
    private static final String BENEFIT_LINES = "(select count(*) from payment_benefit b"
            + " where b.payment_transaction_id = p.payment_transaction_id and b.benefit_value > 0)";
    private static final String CONFIRMED_LINES = "(select count(*) from payment_benefit b"
            + " join card_benefit cb on cb.card_benefit_id = b.card_benefit_id"
            + " where b.payment_transaction_id = p.payment_transaction_id and b.benefit_value > 0 and " + CONFIRMED_LINE + ")";
    private static final String CONFIRMED_AMOUNT = "(select coalesce(sum(b.krw_amount), 0) from payment_benefit b"
            + " join card_benefit cb on cb.card_benefit_id = b.card_benefit_id"
            + " where b.payment_transaction_id = p.payment_transaction_id and " + CONFIRMED_LINE + ")";
    private static final String BENEFIT_AMOUNT = "(select sum(b.krw_amount) from payment_benefit b"
            + " where b.payment_transaction_id = p.payment_transaction_id)";
    /** 추천 1순위(저장된 추천 결과의 첫 후보)로 승인했는지. 결과가 없는 결제(SEED·옛 결제)는 아니다 */
    private static final String YAMU_PICK = "(p.status = 'APPROVED' and p.user_card_id is not null"
            + " and cast(json_unquote(json_extract(p.recommendation_result_json, '$.candidates[0].card.userCardId'))"
            + " as unsigned) = p.user_card_id)";

    private static final String LIST_COLUMNS = """
            select p.payment_transaction_id, p.source, p.status, p.requested_at, p.approved_at, p.canceled_at,
                   p.merchant_id, %s as merchant_name, %s as category_code,
                   p.payment_channel, p.requested_amount, p.approved_amount, p.installment_months,
                   p.recognized_performance_amount, p.performance_month, p.failure_code,
                   p.spending_class, p.memo,
                   p.user_card_id, c.card_alias, c.card_last4, cp.product_name, cp.card_type,
                   p.recommended_rank, p.user_selected,
                   cast(%s as signed) as benefit_amount, %s as benefit_lines, %s as confirmed_lines
            from payment_transaction p
            join merchant m on m.merchant_id = p.merchant_id
            left join user_card c on c.user_card_id = p.user_card_id
            left join card_product cp on cp.card_product_id = p.card_product_id
            """.formatted(MERCHANT_NAME, CATEGORY_CODE, BENEFIT_AMOUNT, BENEFIT_LINES, CONFIRMED_LINES);

    /**
     * 목록 조건(v0.3.1 보완 포함). q는 가맹점명·메모 부분 일치이고, 숫자만이면 결제 금액·승인액이 같은 결제도 찾는다.
     * 업종 코드는 접두어 일치다. LIKE 특수문자는 {@link Filter}가 막아 그대로 찾는다.
     * 표 기본 정렬 규칙(utf8mb4_0900_bin)은 대소문자를 가려서, 검색은 utf8mb4_0900_ai_ci로 비교한다("starbucks"로 "Starbucks"를 찾음).
     * 앞에 %가 붙는 부분 일치라 원래 색인을 쓰지 못하므로 정렬 규칙을 바꿔도 비용은 같다.
     */
    private static final String FILTER = """
            where p.user_id = :userId
              and p.status in (:statuses)
              and (:from is null or p.requested_at >= :from)
              and (:until is null or p.requested_at < :until)
              and (:userCardId is null or p.user_card_id = :userCardId)
              and (:source is null or p.source = :source)
              and (:qLike is null or (%1$s) collate utf8mb4_0900_ai_ci like :qLike
                   or p.memo collate utf8mb4_0900_ai_ci like :qLike
                   or (:qAmount is not null and (p.requested_amount = :qAmount or p.approved_amount = :qAmount)))
              and (:categoryPrefix is null or %2$s like :categoryPrefix)
              and (:spendingClass is null or p.spending_class = :spendingClass)
              and (:channel is null or p.payment_channel = :channel)
              and (:benefitStatus is null
                   or (:benefitStatus = 'NONE' and (p.status <> 'APPROVED' or %3$s = 0))
                   or (:benefitStatus = 'CONFIRMED' and p.status = 'APPROVED' and %3$s > 0 and %4$s = %3$s)
                   or (:benefitStatus = 'EXPECTED' and p.status = 'APPROVED' and %3$s > 0 and %4$s < %3$s))
            """.formatted(MERCHANT_NAME, CATEGORY_CODE, BENEFIT_LINES, CONFIRMED_LINES);

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
            TransactionSource source, String q, Long qAmount, String categoryCode, String spendingClass,
            PaymentChannel channel, BenefitStatus benefitStatus) {

        Filter(Long userId, List<PaymentStatus> statuses, LocalDateTime from, LocalDateTime until, Long userCardId,
                TransactionSource source) {
            this(userId, statuses, from, until, userCardId, source, null, null, null, null, null, null);
        }

        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("statuses", statuses.stream().map(Enum::name).toList())
                    .addValue("from", from)
                    .addValue("until", until)
                    .addValue("userCardId", userCardId)
                    .addValue("source", source == null ? null : source.name())
                    .addValue("qLike", q == null ? null : "%" + escapeLike(q) + "%")
                    .addValue("qAmount", qAmount)
                    .addValue("categoryPrefix", categoryCode == null ? null : escapeLike(categoryCode) + "%")
                    .addValue("spendingClass", spendingClass)
                    .addValue("channel", channel == null ? null : channel.name())
                    .addValue("benefitStatus", benefitStatus == null ? null : benefitStatus.name());
        }

        /** MySQL LIKE의 기본 이스케이프 문자(\)로 %·_·\를 글자 그대로 찾게 한다. */
        private static String escapeLike(String value) {
            return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

    /** 결제의 청구할인 원화 합계(신용카드 청구예정액 = 승인액 − 청구할인). */
    private static final String BILLING_DISCOUNT = "(select coalesce(sum(b.krw_amount), 0) from payment_benefit b"
            + " join card_benefit cb on cb.card_benefit_id = b.card_benefit_id"
            + " where b.payment_transaction_id = p.payment_transaction_id"
            + " and cb.benefit_type = 'DISCOUNT' and cb.application_timing = 'AT_BILLING')";

    /**
     * CSV 내려받기(Task 5.3.6): 목록과 같은 조건·순서로 limit건까지. 목록 칸에 카드 끝자리·승인액·청구할인 합계를 더한다.
     * 한 번의 쿼리로 읽는다.
     */
    List<ExportRow> findForExport(Filter filter, int limit) {
        String columns = LIST_COLUMNS.replaceFirst("\nfrom payment_transaction p",
                java.util.regex.Matcher.quoteReplacement(", cast(" + BILLING_DISCOUNT
                        + " as signed) as billing_discount\nfrom payment_transaction p"));
        return jdbc.query(columns + FILTER + """
                order by p.requested_at desc, p.payment_transaction_id desc
                limit :limit
                """, filter.params().addValue("limit", limit),
                (rs, i) -> new ExportRow(item(rs), rs.getString("card_last4"), rs.getObject("approved_amount", Long.class),
                        rs.getLong("billing_discount")));
    }

    /** CSV 한 줄의 원천. */
    record ExportRow(PaymentListResponse.Item item, String cardLast4, Long approvedAmount, long billingDiscount) {}

    /** 필터를 적용한 전체 개수와 승인 건의 금액·혜택·확정 혜택·소비 분류별 금액·야무픽 건수. */
    Totals totals(Filter filter) {
        return jdbc.queryForObject("""
                select count(*) as cnt,
                       coalesce(sum(case when p.status = 'APPROVED' then p.requested_amount end), 0) as amount,
                       coalesce(sum(case when p.status = 'APPROVED' then %1$s end), 0) as benefit,
                       coalesce(sum(case when p.status = 'APPROVED' then %2$s end), 0) as confirmed_benefit,
                       coalesce(sum(case when p.status = 'APPROVED' and p.spending_class = 'LIVING'
                                         then p.requested_amount end), 0) as living,
                       coalesce(sum(case when p.status = 'APPROVED' and p.spending_class = 'LARGE'
                                         then p.requested_amount end), 0) as large,
                       coalesce(sum(case when p.status = 'APPROVED' and p.spending_class = 'EXCLUDED'
                                         then p.requested_amount end), 0) as excluded,
                       coalesce(sum(case when %3$s then 1 else 0 end), 0) as yamu_pick
                from payment_transaction p
                join merchant m on m.merchant_id = p.merchant_id
                """.formatted(BENEFIT_AMOUNT, CONFIRMED_AMOUNT, YAMU_PICK) + FILTER, filter.params(),
                (rs, i) -> new Totals(rs.getLong("cnt"), rs.getLong("amount"), rs.getLong("benefit"),
                        rs.getLong("confirmed_benefit"), new PaymentListResponse.ByClass(rs.getLong("living"),
                                rs.getLong("large"), rs.getLong("excluded")), rs.getLong("yamu_pick")));
    }

    record Totals(long count, long amount, long benefit, long confirmedBenefit, PaymentListResponse.ByClass byClass,
            long yamuPickCount) {}

    /** 이 사용자의 결제 한 건. 없거나 남의 결제면 empty다. */
    Optional<PaymentRow> findOne(Long paymentId, Long userId) {
        List<PaymentRow> rows = jdbc.query(LIST_COLUMNS + """
                where p.payment_transaction_id = :paymentId and p.user_id = :userId
                """, new MapSqlParameterSource("paymentId", paymentId).addValue("userId", userId),
                (rs, i) -> row(rs));
        return rows.stream().findFirst();
    }

    /**
     * 상세 화면에 쓰는 결제 행. 목록 칸에 상세에서만 쓰는 칸을 더했다.
     *
     * @param recommendedRank 마지막 시도 카드의 추천 순위. 추천 밖 카드·기록 전 결제·SEED는 null
     * @param userSelected    마지막 시도의 카드를 직접 골랐는지. 기록 전 결제·SEED는 null
     */
    record PaymentRow(PaymentListResponse.Item item, PaymentChannel channel, Long approvedAmount,
            Long recognizedPerformanceAmount, LocalDate performanceMonth, LocalDateTime canceledAt,
            String failureCode, String cardLast4, Integer recommendedRank, Boolean userSelected) {}

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
                rs.getString("failure_code"), rs.getString("card_last4"),
                rs.getObject("recommended_rank", Integer.class), rs.getObject("user_selected", Boolean.class));
    }

    private static PaymentListResponse.Item item(ResultSet rs) throws SQLException {
        PaymentStatus status = PaymentStatus.valueOf(rs.getString("status"));
        Integer months = rs.getObject("installment_months", Integer.class);
        boolean installment = months != null && months > 1;
        Long recognized = rs.getObject("recognized_performance_amount", Long.class);
        Long benefit = rs.getObject("benefit_amount", Long.class);
        BenefitStatus benefitStatus = benefitStatus(status, rs.getLong("benefit_lines"), rs.getLong("confirmed_lines"));
        String categoryCode = rs.getString("category_code");
        return new PaymentListResponse.Item(
                rs.getLong("payment_transaction_id"),
                TransactionSource.valueOf(rs.getString("source")),
                status,
                rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("approved_at", LocalDateTime.class),
                new PaymentListResponse.Merchant(rs.getLong("merchant_id"), rs.getString("merchant_name"),
                        categoryCode, categoryCode == null ? null : CATEGORY_NAMES.get(categoryCode)),
                rs.getLong("requested_amount"),
                installment ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM,
                installment ? months : null,
                card(rs),
                status != PaymentStatus.APPROVED || benefit == null ? 0 : benefit,
                benefitStatus == BenefitStatus.NONE ? null : benefitStatus == BenefitStatus.CONFIRMED,
                status == PaymentStatus.APPROVED && recognized != null && recognized > 0,
                benefitStatus,
                rs.getString("spending_class"),
                rs.getString("memo"),
                PaymentChannel.valueOf(rs.getString("payment_channel")),
                categoryCode);
    }

    /** 승인 건만 혜택이 있다. 혜택 줄이 모두 확정이면 CONFIRMED, 하나라도 아니면 EXPECTED. */
    static BenefitStatus benefitStatus(PaymentStatus status, long lines, long confirmedLines) {
        if (status != PaymentStatus.APPROVED || lines == 0) {
            return BenefitStatus.NONE;
        }
        return confirmedLines == lines ? BenefitStatus.CONFIRMED : BenefitStatus.EXPECTED;
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
