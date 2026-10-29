package com.yamu.be.account;

import com.yamu.be.account.dto.AccountResponse.LinkedCard;
import com.yamu.be.account.dto.AccountResponse.UpcomingWithdrawal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 계좌 목록에 붙는 카드·청구 정보를 읽기만 한다. 카드(user_card·card_product)와 청구(card_bill, BE 2)는 계좌 도메인 밖이라
 * 엔티티를 끌어오지 않고 필요한 칸만 SQL로 읽는다. 계좌 여러 개를 IN 한 번으로 묶어 읽는다.
 */
@Component
@RequiredArgsConstructor
class AccountActivityReader {

    private final NamedParameterJdbcTemplate jdbc;

    /** 계좌별 등록 카드(ACTIVE·INACTIVE). 카드 목록과 같은 순서(정한 순서 → 등록 순). 별칭이 없으면 상품명. */
    Map<Long, List<LinkedCard>> linkedCards(Long userId, Collection<Long> accountIds) {
        Map<Long, List<LinkedCard>> byAccount = new HashMap<>();
        if (accountIds.isEmpty()) {
            return byAccount;
        }
        jdbc.query("""
                SELECT uc.linked_account_id, uc.user_card_id, COALESCE(uc.card_alias, cp.product_name), cp.card_type
                FROM user_card uc JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                WHERE uc.user_id = :userId AND uc.linked_account_id IN (:ids) AND uc.status IN ('ACTIVE','INACTIVE')
                ORDER BY uc.display_order IS NULL, uc.display_order, uc.registered_at, uc.user_card_id
                """, params(userId, accountIds), rs -> {
                    String role = "CHECK".equals(rs.getString(4)) ? "CHECK_LINKED" : "CREDIT_SETTLEMENT";
                    byAccount.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                            .add(new LinkedCard(rs.getLong(2), rs.getString(3), role));
                });
        return byAccount;
    }

    /** 계좌별 확정됐지만 아직 출금되지 않은 청구서(CONFIRMED). 청구 확정 당시 출금 계좌 기준, 출금일 순. */
    Map<Long, List<UpcomingWithdrawal>> upcomingWithdrawals(Long userId, Collection<Long> accountIds) {
        Map<Long, List<UpcomingWithdrawal>> byAccount = new HashMap<>();
        if (accountIds.isEmpty()) {
            return byAccount;
        }
        jdbc.query("""
                SELECT linked_account_id, card_bill_id, confirmed_amount, withdrawal_date FROM card_bill
                WHERE user_id = :userId AND linked_account_id IN (:ids) AND status = 'CONFIRMED'
                ORDER BY withdrawal_date, card_bill_id
                """, params(userId, accountIds), rs -> {
                    byAccount.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                            .add(new UpcomingWithdrawal(rs.getLong(2), (Long) rs.getObject(3),
                                    rs.getObject(4, LocalDate.class)));
                });
        return byAccount;
    }

    private static MapSqlParameterSource params(Long userId, Collection<Long> accountIds) {
        return new MapSqlParameterSource("userId", userId).addValue("ids", accountIds);
    }
}
