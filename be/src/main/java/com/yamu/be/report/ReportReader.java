package com.yamu.be.report;

import com.yamu.be.payment.history.PaymentSqlFragments;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 소비 리포트용 결제 원장을 SQL로 읽는다. 조회 전용이라 엔티티를 거치지 않는다.
 *
 * <p>모든 쿼리에 {@code user_id}를 넣어 남의 결제를 읽지 않는다. 기간은 결제 내역과 같은 POS 요청 시각(UTC)이다.
 * 판정 조각(업종·혜택 확정)은 결제 내역과 같은 {@link PaymentSqlFragments}를 쓴다.
 */
@Component
class ReportReader {

    /** 저장된 추천 결과의 n번째 후보 칸(JSON 문자열, 없으면 NULL). */
    private static String candidate(int index, String path) {
        return "json_unquote(json_extract(p.recommendation_result_json, '$.candidates[" + index + "]." + path + "'))";
    }

    private static final String MONTH_ROWS = """
            select p.payment_transaction_id, p.source, p.requested_amount, p.spending_class, p.user_card_id,
                   p.recommended_rank, p.user_selected, p.approved_at, %s as merchant_name, %s as category_code,
                   cast(%s as signed) as benefit_amount, %s as confirmed_amount,
                   %s as benefit_lines, %s as confirmed_lines, %s as unconverted_lines,
                   %s as best_card_id, %s as best_net_benefit, %s as second_net_benefit
            from payment_transaction p
            join merchant m on m.merchant_id = p.merchant_id
            where p.user_id = :userId
              and p.status = 'APPROVED'
              and p.requested_at >= :from and p.requested_at < :until
            order by p.requested_at, p.payment_transaction_id
            """.formatted(PaymentSqlFragments.MERCHANT_NAME, PaymentSqlFragments.CATEGORY_CODE,
            PaymentSqlFragments.BENEFIT_AMOUNT, PaymentSqlFragments.CONFIRMED_AMOUNT,
            PaymentSqlFragments.BENEFIT_LINES, PaymentSqlFragments.CONFIRMED_LINES,
            PaymentSqlFragments.UNCONVERTED_LINES,
            candidate(0, "card.userCardId"), candidate(0, "netBenefit"), candidate(1, "netBenefit"));

    private final NamedParameterJdbcTemplate jdbc;

    ReportReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 기간 안의 승인 결제(취소·거절 제외). 리포트 한 달치라 행 수가 많지 않아 한 번에 읽고 서비스에서 묶는다.
     *
     * @param from  요청 시각 하한(UTC, 포함)
     * @param until 요청 시각 상한(UTC, 미포함)
     */
    List<PaymentRow> approvedPayments(long userId, LocalDateTime from, LocalDateTime until) {
        return jdbc.query(MONTH_ROWS, period(userId, from, until), (rs, i) -> row(rs));
    }

    /** 기간 안의 승인 결제 금액(즉시할인 전 요청 금액) 합계와 그중 생활 소비(LIVING) 합계. */
    Spent spent(long userId, LocalDateTime from, LocalDateTime until) {
        return jdbc.queryForObject("""
                select coalesce(sum(p.requested_amount), 0) as total,
                       coalesce(sum(case when p.spending_class = 'LIVING' then p.requested_amount end), 0) as living
                from payment_transaction p
                where p.user_id = :userId
                  and p.status = 'APPROVED'
                  and p.requested_at >= :from and p.requested_at < :until
                """, period(userId, from, until), (rs, i) -> new Spent(rs.getLong("total"), rs.getLong("living")));
    }

    record Spent(long total, long living) {}

    /**
     * 승인 결제 한 건.
     *
     * @param amount           POS가 요청한 결제 금액(즉시할인 전, 결제 내역 목록과 같은 기준)
     * @param recommendedRank  승인한 카드의 추천 순위(승인 때 기록). 추천 밖 카드·기록 전 결제는 null
     * @param userSelected     사용자가 카드를 직접 골랐는지(승인 때 기록). 순위 칸이 생기기 전(V20261109_4) 결제는 null
     * @param benefitAmount    혜택 원화 합계. 혜택 줄이 없으면 null
     * @param unconvertedLines 원화로 환산되지 않은 혜택 줄 수
     * @param bestCardId       저장된 추천 결과의 1순위 카드. 추천 결과가 없으면 null
     * @param bestNetBenefit   1순위 순혜택(원, 소수 가능). 없으면 null
     * @param secondNetBenefit 2순위 순혜택. 후보가 하나뿐이면 null
     */
    record PaymentRow(long paymentId, String source, long amount, String spendingClass, Long userCardId,
            Integer recommendedRank, Boolean userSelected, LocalDateTime approvedAt, String merchantName,
            String categoryCode, Long benefitAmount,
            long confirmedAmount, long benefitLines, long confirmedLines, long unconvertedLines, Long bestCardId,
            BigDecimal bestNetBenefit, BigDecimal secondNetBenefit) {}

    private static MapSqlParameterSource period(long userId, LocalDateTime from, LocalDateTime until) {
        return new MapSqlParameterSource("userId", userId).addValue("from", from).addValue("until", until);
    }

    private static PaymentRow row(ResultSet rs) throws SQLException {
        return new PaymentRow(rs.getLong("payment_transaction_id"), rs.getString("source"),
                rs.getLong("requested_amount"), rs.getString("spending_class"),
                rs.getObject("user_card_id", Long.class), rs.getObject("recommended_rank", Integer.class),
                rs.getObject("user_selected", Boolean.class), rs.getObject("approved_at", LocalDateTime.class),
                rs.getString("merchant_name"), rs.getString("category_code"),
                rs.getObject("benefit_amount", Long.class), rs.getLong("confirmed_amount"),
                rs.getLong("benefit_lines"), rs.getLong("confirmed_lines"), rs.getLong("unconverted_lines"),
                longOrNull(rs.getString("best_card_id")), decimalOrNull(rs.getString("best_net_benefit")),
                decimalOrNull(rs.getString("second_net_benefit")));
    }

    /** JSON 칸이 없거나 null이거나 숫자가 아니면 null(추천 결과가 깨져도 리포트 전체를 실패시키지 않는다). */
    private static BigDecimal decimalOrNull(String value) {
        if (value == null || value.equals("null")) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(String value) {
        BigDecimal number = decimalOrNull(value);
        if (number == null) {
            return null;
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
