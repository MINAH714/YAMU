package com.yamu.be.payment.lookup;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제에 필요한 보유 카드 정보를 user_card에서 직접 읽는다.
 *
 * <p>카드 엔티티는 BE1 담당이라 엔티티를 공유하지 않고 ID와 SQL로만 조회한다.
 * 카드 한 장을 골라 소유를 확인하는 것(없는·남의 카드는 404)은 BE1의 {@code CardQueryService.requireRegisteredCard}를 쓴다.
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

    /**
     * 추천 후보가 되는 사용자의 ACTIVE 카드. 기본 카드 → 표시 순서(없으면 뒤) → 카드 번호 순이다.
     * 카드사 정지·만료 카드도 돌려주므로 받는 쪽이 {@link ActiveCard#issuerStatus()}로 거른다.
     */
    public List<ActiveCard> findActiveCards(Long userId) {
        return jdbc.query("""
                select c.user_card_id, c.card_product_id, p.card_type, c.issuer_status, c.is_default, c.display_order,
                       c.card_alias, c.card_last4, p.issuer_code, p.product_name
                from user_card c
                join card_product p on p.card_product_id = c.card_product_id
                where c.user_id = ? and c.status = 'ACTIVE'
                order by c.is_default desc, c.display_order is null, c.display_order, c.user_card_id
                """, (rs, i) -> new ActiveCard(
                rs.getLong("user_card_id"),
                rs.getLong("card_product_id"),
                CardType.valueOf(rs.getString("card_type")),
                CardIssuerStatus.valueOf(rs.getString("issuer_status")),
                rs.getBoolean("is_default"),
                rs.getObject("display_order", Integer.class),
                rs.getString("card_alias"),
                rs.getString("card_last4"),
                rs.getString("issuer_code"),
                rs.getString("product_name")), userId);
    }

    /**
     * 결제에 쓴 카드의 영수증 표시값. 결제 뒤 카드를 해제·제외했어도 결제 기록은 보여야 하므로 카드 상태로 거르지 않는다.
     * 소유 확인을 하지 않으므로 결제 행의 user_card_id로만 부른다.
     */
    public Optional<ReceiptCard> findReceiptCard(Long userCardId) {
        return jdbc.query("""
                select p.issuer_code, p.card_type, c.card_last4
                from user_card c
                join card_product p on p.card_product_id = c.card_product_id
                where c.user_card_id = ?
                """, (rs, i) -> new ReceiptCard(
                rs.getString("issuer_code"),
                CardType.valueOf(rs.getString("card_type")),
                rs.getString("card_last4")), userCardId).stream().findFirst();
    }

    /** 영수증에 보일 카드. 사용자 별칭은 넣지 않는다. */
    public record ReceiptCard(String issuerCode, CardType cardType, String last4) {}

    /**
     * 추천에 필요한 보유 카드 한 장.
     *
     * @param defaultCard  사용자가 정한 기본 카드인지(사용자당 최대 1장)
     * @param displayOrder 보유 카드 목록 표시 순서. 저장 전이면 null
     * @param alias        사용자가 정한 별칭. 없으면 null
     */
    public record ActiveCard(Long userCardId, Long cardProductId, CardType cardType, CardIssuerStatus issuerStatus,
            boolean defaultCard, Integer displayOrder, String alias, String last4, String issuerCode,
            String productName) {}
}
