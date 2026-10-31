package com.yamu.be.payment.lookup;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제에 필요한 보유 카드 정보를 user_card에서 직접 읽는다.
 *
 * <p>카드 엔티티는 BE1 담당이라, 예정 내역(cashflow)처럼 다른 도메인 테이블은 ID와 SQL로만 조회한다.
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
                       c.card_alias, c.card_last4, p.issuer_code, p.product_name,
                       (select count(*) from card_benefit b where b.card_product_id = p.card_product_id) as benefit_count
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
                rs.getString("product_name"),
                rs.getLong("benefit_count")), userId);
    }

    /**
     * 추천에 필요한 보유 카드 한 장.
     *
     * @param defaultCard  사용자가 정한 기본 카드인지(사용자당 최대 1장)
     * @param displayOrder 보유 카드 목록 표시 순서. 저장 전이면 null
     * @param alias        사용자가 정한 별칭. 없으면 null
     * @param benefitCount 상품에 등록된 혜택 규칙 행 수. 0이면 혜택 자료가 없는 상품
     */
    public record ActiveCard(Long userCardId, Long cardProductId, CardType cardType, CardIssuerStatus issuerStatus,
            boolean defaultCard, Integer displayOrder, String alias, String last4, String issuerCode,
            String productName, long benefitCount) {}
}
