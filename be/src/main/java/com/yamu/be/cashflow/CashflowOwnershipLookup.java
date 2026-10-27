package com.yamu.be.cashflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class CashflowOwnershipLookup {
    private final JdbcTemplate jdbc;

    CashflowOwnershipLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean hasConnectedAccount(Long userId, Long accountId) {
        Long count = jdbc.queryForObject("""
                select count(*) from linked_account
                where user_id = ? and linked_account_id = ? and status = 'CONNECTED'
                """, Long.class, userId, accountId);
        return count != null && count > 0;
    }

    boolean hasActiveCard(Long userId, Long userCardId) {
        Long count = jdbc.queryForObject("""
                select count(*) from user_card
                where user_id = ? and user_card_id = ? and status = 'ACTIVE'
                """, Long.class, userId, userCardId);
        return count != null && count > 0;
    }
}
