package com.yamu.be.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 알림 문구에 넣을 가맹점·카드 이름·청구 월을 읽는다. 결제·카드·청구 모듈의 표를 읽기만 한다(잔고예보 {@code CardNameReader}와 같은 방식).
 * 모든 SQL에 사용자 번호를 조건으로 넣어, 사건의 사용자와 다른 사람의 행으로 문구를 만들지 않는다.
 *
 * <p>카드 이름은 별칭, 없거나 비었으면 상품명이다(앱 카드 목록과 같다).
 */
@Component
class NotificationContentReader {

    private static final String CARD_NAME = "COALESCE(NULLIF(TRIM(uc.card_alias), ''), cp.product_name)";

    private final NamedParameterJdbcTemplate jdbc;

    NotificationContentReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param approvedAmount 결제 금액 − 즉시할인. 승인 전이면 null
     * @param cardName       결제한 카드. 카드가 정해지지 않았으면 null
     */
    record Payment(String merchantName, long requestedAmount, Long approvedAmount, String cardName) {}

    /** @param billingMonth 청구 월(한국 날짜, 1일) */
    record Bill(LocalDate billingMonth, String cardName) {}

    Optional<Payment> payment(long userId, long paymentId) {
        List<Payment> rows = jdbc.query("""
                SELECT m.merchant_name, p.requested_amount, p.approved_amount, %s AS card_name
                  FROM payment_transaction p
                  JOIN merchant m ON m.merchant_id = p.merchant_id
                  LEFT JOIN user_card uc ON uc.user_card_id = p.user_card_id AND uc.user_id = p.user_id
                  LEFT JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                 WHERE p.payment_transaction_id = :paymentId AND p.user_id = :userId
                """.formatted(CARD_NAME), new MapSqlParameterSource("paymentId", paymentId).addValue("userId", userId),
                (rs, i) -> new Payment(rs.getString("merchant_name"), rs.getLong("requested_amount"),
                        rs.getObject("approved_amount", Long.class), rs.getString("card_name")));
        return rows.stream().findFirst();
    }

    Optional<Bill> bill(long userId, long cardBillId) {
        List<Bill> rows = jdbc.query("""
                SELECT b.billing_month, %s AS card_name
                  FROM card_bill b
                  JOIN user_card uc ON uc.user_card_id = b.user_card_id AND uc.user_id = b.user_id
                  JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                 WHERE b.card_bill_id = :billId AND b.user_id = :userId
                """.formatted(CARD_NAME), new MapSqlParameterSource("billId", cardBillId).addValue("userId", userId),
                (rs, i) -> new Bill(rs.getObject("billing_month", LocalDate.class), rs.getString("card_name")));
        return rows.stream().findFirst();
    }

    /**
     * 잔고 위험 판정에 쓸 사용자의 연결 계좌. 기준 계좌를 먼저, 그다음 번호 순으로 최대 {@code limit}개다.
     * 임시(2026-11-10): 앱이 잔고예보에서 고른 계좌는 서버에 저장되지 않아, 연결된 계좌 전체로 판정한다.
     */
    List<Long> connectedAccounts(long userId, int limit) {
        return jdbc.queryForList("""
                SELECT linked_account_id
                  FROM linked_account
                 WHERE user_id = :userId AND status = 'CONNECTED'
                 ORDER BY is_primary DESC, linked_account_id
                 LIMIT :limit
                """, new MapSqlParameterSource("userId", userId).addValue("limit", limit), Long.class);
    }
}
