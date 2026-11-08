package com.yamu.be.cashflow;

import com.yamu.be.payment.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 예정 지출 회차와 실제 결제의 연결({@code cashflow_payment_match})과 연결에 필요한 결제 정보를 SQL로 읽고 쓴다.
 * 결제·가맹점·카드 테이블은 결제 기능 소유라 읽기만 한다. 모든 조회는 user_id로 범위를 좁힌다.
 */
@Repository
class PaymentMatchStore {

    /**
     * 가맹점 이름·업종은 결제 목록·상세(PaymentHistoryReader)와 같이 결제 당시 기록을 먼저 쓴다.
     * 그래야 두 화면의 이름이 같고, 업종 일치도 결제 당시 업종으로 판정한다(!163 리뷰).
     */
    private static final String PAYMENT_COLUMNS = """
            SELECT p.payment_transaction_id, p.user_card_id, cp.card_type,
                   coalesce(nullif(p.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)
                       AS merchant_name,
                   coalesce(nullif(p.merchant_context_snapshot ->> '$.categoryCode', 'null'), m.merchant_category_code)
                       AS merchant_category_code,
                   p.requested_amount, p.approved_amount, p.approved_at, p.source, p.status
              FROM payment_transaction p
              JOIN merchant m ON m.merchant_id = p.merchant_id
              LEFT JOIN card_product cp ON cp.card_product_id = p.card_product_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PaymentMatchStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 사용자의 결제 한 건. 없거나 다른 사용자의 결제면 비어 있다. */
    Optional<Payment> payment(long userId, long paymentId) {
        return jdbc.query(PAYMENT_COLUMNS + " WHERE p.payment_transaction_id = :paymentId AND p.user_id = :userId",
                Map.of("paymentId", paymentId, "userId", userId), PaymentMatchStore::payment).stream().findFirst();
    }

    /**
     * 승인 시각이 [fromUtc, untilUtc)인 사용자의 승인된 YAMU 결제 중 아직 어떤 회차와도 연결되지 않은 것.
     * 시드 결제는 앱에서 고른 실제 결제가 아니라 빼고, 승인 시각 순이다.
     */
    List<Payment> unmatchedApprovedBetween(long userId, LocalDateTime fromUtc, LocalDateTime untilUtc) {
        return jdbc.query(PAYMENT_COLUMNS + """
                 WHERE p.user_id = :userId AND p.source = 'YAMU' AND p.status = 'APPROVED'
                   AND p.approved_at >= :fromUtc AND p.approved_at < :untilUtc
                   AND NOT EXISTS (SELECT 1 FROM cashflow_payment_match x
                                    WHERE x.payment_transaction_id = p.payment_transaction_id)
                 ORDER BY p.approved_at, p.payment_transaction_id
                """, Map.of("userId", userId, "fromUtc", fromUtc, "untilUtc", untilUtc), PaymentMatchStore::payment);
    }

    /** 회차에 연결된 매칭. 회차당 하나뿐이다(uk_match_occurrence). */
    Optional<Match> byOccurrence(long occurrenceId) {
        return jdbc.query("""
                SELECT cashflow_payment_match_id, cashflow_occurrence_id, payment_transaction_id, matched_at
                  FROM cashflow_payment_match WHERE cashflow_occurrence_id = :occurrenceId
                """, Map.of("occurrenceId", occurrenceId), PaymentMatchStore::match).stream().findFirst();
    }

    /** 결제에 연결된 매칭. 결제당 하나뿐이다(uk_match_payment). */
    Optional<Match> byPayment(long paymentId) {
        return jdbc.query("""
                SELECT cashflow_payment_match_id, cashflow_occurrence_id, payment_transaction_id, matched_at
                  FROM cashflow_payment_match WHERE payment_transaction_id = :paymentId
                """, Map.of("paymentId", paymentId), PaymentMatchStore::match).stream().findFirst();
    }

    /**
     * 매칭을 저장하고 ID를 돌려준다. 회차나 결제가 이미 연결돼 있으면 유일 키 위반
     * ({@link org.springframework.dao.DuplicateKeyException})이 난다.
     */
    long insert(long userId, long occurrenceId, long paymentId, LocalDateTime matchedAtUtc) {
        var keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO cashflow_payment_match
                    (user_id, cashflow_occurrence_id, payment_transaction_id, matched_at, created_at)
                VALUES (:userId, :occurrenceId, :paymentId, :matchedAt, :matchedAt)
                """, new MapSqlParameterSource(Map.of("userId", userId, "occurrenceId", occurrenceId,
                "paymentId", paymentId, "matchedAt", matchedAtUtc)), keys, new String[] {"cashflow_payment_match_id"});
        return keys.getKey().longValue();
    }

    /** 사용자의 매칭을 지우고 지운 행 수(0 또는 1)를 돌려준다. */
    int delete(long userId, long matchId) {
        return jdbc.update("DELETE FROM cashflow_payment_match WHERE cashflow_payment_match_id = :matchId "
                + "AND user_id = :userId", Map.of("matchId", matchId, "userId", userId));
    }

    private static Payment payment(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getLong("payment_transaction_id"), (Long) rs.getObject("user_card_id", Long.class),
                rs.getString("card_type"), rs.getString("merchant_name"), rs.getString("merchant_category_code"),
                rs.getLong("requested_amount"), (Long) rs.getObject("approved_amount", Long.class),
                rs.getObject("approved_at", LocalDateTime.class), rs.getString("source"),
                PaymentStatus.valueOf(rs.getString("status")));
    }

    private static Match match(ResultSet rs, int row) throws SQLException {
        return new Match(rs.getLong("cashflow_payment_match_id"), rs.getLong("cashflow_occurrence_id"),
                rs.getLong("payment_transaction_id"), rs.getObject("matched_at", LocalDateTime.class));
    }

    /**
     * 결제 한 건. requestedAmount는 POS가 요청한 결제 금액(즉시할인 전, 결제 목록의 amount)이고, approvedAmount는
     * 즉시할인 뒤 승인 금액이다. approvedAtUtc·approvedAmount는 승인 전이면 null, cardType은 카드 선택 전이면 null이다.
     */
    record Payment(long paymentId, Long userCardId, String cardType, String merchantName,
            String merchantCategoryCode, long requestedAmount, Long approvedAmount, LocalDateTime approvedAtUtc,
            String source,
            PaymentStatus status) {
    }

    record Match(long matchId, long occurrenceId, long paymentId, LocalDateTime matchedAtUtc) {
    }
}
