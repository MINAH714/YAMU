package com.yamu.be.card;

import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBill;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 카드 관리 화면에 같이 보여 줄 청구·실적을 읽는다. 청구(card_bill)는 BE 2 엔티티 {@code CardBill}을
 * {@link CardBillQueryRepository}로 읽고, 실적(card_monthly_performance)은 엔티티가 없어 SQL로 읽기만 한다.
 */
@Component
@RequiredArgsConstructor
public class CardActivityReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final CardBillQueryRepository bills;

    /** 카드별 한 달 실적(한국 기준 월). 집계된 행이 없는 카드는 빠진다. */
    public Map<Long, Long> recognizedAmounts(Collection<Long> userCardIds, YearMonth month) {
        Map<Long, Long> amounts = new HashMap<>();
        if (userCardIds.isEmpty()) {
            return amounts;
        }
        jdbc.query("""
                SELECT user_card_id, recognized_amount FROM card_monthly_performance
                WHERE user_card_id IN (:ids) AND performance_month = :month
                """, new MapSqlParameterSource("ids", userCardIds).addValue("month", Date.valueOf(month.atDay(1))),
                rs -> {
                    amounts.put(rs.getLong(1), rs.getLong(2));
                });
        return amounts;
    }

    /** 미납 청구가 있는 카드. */
    Set<Long> cardsWithUnpaidBill(Collection<Long> userCardIds) {
        if (userCardIds.isEmpty()) {
            return new HashSet<>();
        }
        return bills.findByUserCardIdInAndStatus(userCardIds, BillStatus.UNPAID).stream()
                .map(CardBill::getUserCardId).collect(Collectors.toCollection(HashSet::new));
    }

    /** 끝나지 않은 청구서 번호(ACCUMULATING·확정·미납 등 {@link CardBillQueryRepository#SETTLED}가 아닌 것), 번호 순. */
    List<Long> unsettledBillIds(Long userCardId) {
        return bills.findByUserCardIdAndStatusNotInOrderByIdAsc(userCardId, CardBillQueryRepository.SETTLED).stream()
                .map(CardBill::getId).toList();
    }

    /** 출금일이 가장 가까운 확정 청구서. 없으면 empty. */
    Optional<NextWithdrawal> nextWithdrawal(Long userCardId) {
        return bills.findFirstByUserCardIdAndStatusOrderByWithdrawalDateAscIdAsc(userCardId, BillStatus.CONFIRMED)
                .map(b -> new NextWithdrawal(b.getId(), b.getConfirmedAmount(), b.getWithdrawalDate(),
                        b.getStatus().name()));
    }

    record NextWithdrawal(Long billId, Long amount, LocalDate withdrawalDate, String status) {
    }
}
