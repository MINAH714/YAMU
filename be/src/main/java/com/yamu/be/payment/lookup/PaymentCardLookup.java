package com.yamu.be.payment.lookup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제에 필요한 보유 카드 정보를 user_card에서 직접 읽는다.
 *
 * <p>카드 엔티티는 BE1 담당이라 엔티티를 공유하지 않고 ID와 SQL로만 조회한다.
 */
@Component
public class PaymentCardLookup {

    private final JdbcTemplate jdbc;

    public PaymentCardLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 추천·결제 대상인 ACTIVE 카드 수. API 명세 V2 2장 {@code UserCardStatus}. */
    public long countActiveCards(Long userId) {
        Long count = jdbc.queryForObject("""
                select count(*) from user_card
                where user_id = ? and status = 'ACTIVE'
                """, Long.class, userId);
        return count == null ? 0 : count;
    }
}
