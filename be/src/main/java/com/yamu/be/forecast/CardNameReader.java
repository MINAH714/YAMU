package com.yamu.be.forecast;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시뮬레이션 응답의 카드 이름(별칭, 없으면 상품명)을 읽는다. BE 1 카드 조회({@code UserCardRef})에 이름이 없어
 * user_card·card_product를 읽기만 한다(2026-11-05 결정, 생활비 원천과 같은 방식).
 */
@Component
class CardNameReader {

    private final JdbcTemplate jdbc;

    CardNameReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 사용자 본인 카드의 화면 이름.
     *
     * @return 별칭, 없거나 비었으면 상품명. 남의 카드·없는 카드는 null
     */
    String name(long userId, long userCardId) {
        List<String> names = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(TRIM(uc.card_alias), ''), cp.product_name)
                  FROM user_card uc
                  JOIN card_product cp ON cp.card_product_id = uc.card_product_id
                 WHERE uc.user_card_id = ? AND uc.user_id = ?
                """, String.class, userCardId, userId);
        return names.isEmpty() ? null : names.get(0);
    }
}
