package com.yamu.be.payment.billing.confirm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 청구할인 원천(Task 5.4.7, 2026-11-06 결정). 청구서의 청구 전(PENDING) 회차가 속한 결제마다, 승인 원장(S15P31E103-201)이 적은
 * 청구할인({@code payment_benefit} 중 {@code card_benefit.benefit_type = DISCOUNT}, {@code application_timing = AT_BILLING})을
 * 결제의 모든 회차 원금 비율로 나누고({@link DiscountSplit}) 이 청구서 회차의 몫만 돌려준다.
 *
 * <ul>
 *   <li>한도는 승인 때 이미 차감됐으므로 다시 계산하지 않는다(명세 「청구서 상세」 discounts). 승인 원장의 금액을 그대로 쓴다.</li>
 *   <li>원화 환산액({@code krw_amount})이 없는 할인은 금액을 알 수 없어 넣지 않고 남긴다.</li>
 *   <li>회차 몫이 회차 청구액보다 크면 청구액까지만 넣고 남긴다(결정 Q3). 비율 배분에서는 할인 ≤ 결제 원금이면 생기지 않는다.</li>
 *   <li>승인 상태(APPROVED) 결제만 본다. 확정 전 취소된 결제의 회차는 EXCLUDED라 대상이 아니다.</li>
 *   <li>한계(결정 Q7): 승인 원장이 공유 한도 배분 미확정 묶음을 {@code payment_benefit}에 적지 않으므로 그 묶음의 청구할인은 빠진다.</li>
 * </ul>
 */
@Slf4j
@Component
public class JdbcBillingDiscountSource implements BillingDiscountSource {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBillingDiscountSource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Allocation> allocate(Long cardBillId) {
        List<PendingItem> pending = jdbc.query("""
                SELECT i.bill_item_id, i.payment_transaction_id, COALESCE(i.billed_amount, i.principal_amount) AS billed
                  FROM bill_item i
                 WHERE i.card_bill_id = :bill AND i.status = 'PENDING'
                """, new MapSqlParameterSource("bill", cardBillId),
                (rs, n) -> new PendingItem(rs.getLong("bill_item_id"), rs.getLong("payment_transaction_id"),
                        rs.getLong("billed")));
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Long> paymentIds = pending.stream().map(PendingItem::paymentId).distinct().toList();
        List<Discount> discounts = jdbc.query("""
                SELECT pb.payment_benefit_id, pb.payment_transaction_id, pb.krw_amount, cb.benefit_name
                  FROM payment_benefit pb
                  JOIN card_benefit cb ON cb.card_benefit_id = pb.card_benefit_id
                  JOIN payment_transaction t ON t.payment_transaction_id = pb.payment_transaction_id
                 WHERE pb.payment_transaction_id IN (:payments)
                   AND cb.benefit_type = 'DISCOUNT' AND cb.application_timing = 'AT_BILLING'
                   AND t.status = 'APPROVED'
                 ORDER BY pb.payment_benefit_id
                """, new MapSqlParameterSource("payments", paymentIds),
                (rs, n) -> new Discount(rs.getLong("payment_benefit_id"), rs.getLong("payment_transaction_id"),
                        rs.getObject("krw_amount", Long.class), rs.getString("benefit_name")));
        if (discounts.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Installment>> installments = jdbc.query("""
                SELECT bill_item_id, payment_transaction_id, installment_no, principal_amount
                  FROM bill_item
                 WHERE payment_transaction_id IN (:payments)
                """, new MapSqlParameterSource("payments", paymentIds),
                (rs, n) -> new Installment(rs.getLong("bill_item_id"), rs.getLong("payment_transaction_id"),
                        rs.getInt("installment_no"), rs.getLong("principal_amount")))
                .stream().collect(Collectors.groupingBy(Installment::paymentId));
        Map<Long, PendingItem> pendingById = pending.stream()
                .collect(Collectors.toMap(PendingItem::billItemId, item -> item));

        List<Allocation> allocations = new ArrayList<>();
        Map<Long, Long> givenByItem = new java.util.HashMap<>();
        for (Discount discount : discounts) {
            if (discount.krwAmount() == null) {
                log.warn("원화 환산액이 없는 청구할인이라 청구에 넣지 않음 cardBillId={} paymentBenefitId={}", cardBillId,
                        discount.paymentBenefitId());
                continue;
            }
            if (discount.krwAmount() <= 0) {
                continue;
            }
            List<Installment> rows = installments.getOrDefault(discount.paymentId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(Installment::installmentNo)).toList();
            List<Long> shares = DiscountSplit.split(discount.krwAmount(),
                    rows.stream().map(Installment::principal).toList());
            for (int i = 0; i < rows.size(); i++) {
                PendingItem item = pendingById.get(rows.get(i).billItemId());
                long share = shares.get(i);
                if (item == null || share <= 0) {
                    continue;
                }
                long room = item.billed() - givenByItem.getOrDefault(item.billItemId(), 0L);
                if (share > room) {
                    log.warn("청구할인 몫이 회차 청구액보다 커 청구액까지만 넣음 cardBillId={} billItemId={} share={} room={}",
                            cardBillId, item.billItemId(), share, room);
                    share = Math.max(room, 0);
                }
                if (share > 0) {
                    givenByItem.merge(item.billItemId(), share, Long::sum);
                    allocations.add(new Allocation(item.billItemId(), discount.paymentId(),
                            discount.paymentBenefitId(), discount.name(), share));
                }
            }
        }
        return allocations;
    }

    private record PendingItem(long billItemId, long paymentId, long billed) {}

    private record Discount(long paymentBenefitId, long paymentId, Long krwAmount, String name) {}

    private record Installment(long billItemId, long paymentId, int installmentNo, long principal) {}
}
