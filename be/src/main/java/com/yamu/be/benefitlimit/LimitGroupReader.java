package com.yamu.be.benefitlimit;

import com.yamu.be.recommendation.BenefitPolicy;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 한도 그룹(BE 3 데이터)을 화면에 보여 줄 이름과 함께 읽는다. BE 3 엔티티 {@code LimitGroup}에는 {@code group_name}이 없어,
 * 엔티티를 고치지 않고 조회 전용 SQL로 읽는다.
 */
@Component
class LimitGroupReader {

    private final NamedParameterJdbcTemplate jdbc;

    LimitGroupReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 상품들의 한도 그룹 중 오늘 유효할 수 있는 버전 전부(기간 단위 무관). 시작·종료일 미확인(NULL)은 막지 않는다
     * ({@code CardBenefitRepository.findPossiblyEffective}와 같은 기준). 같은 그룹 코드의 버전 고르기는 호출하는 쪽이 한다.
     */
    List<Row> possiblyEffective(Collection<Long> productIds, LocalDate today) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT limit_group_id, card_product_id, group_code, group_name, period_type, limit_unit, base_limit_value
                FROM limit_group
                WHERE card_product_id IN (:products)
                  AND (valid_from IS NULL OR valid_from <= :today)
                  AND (valid_until IS NULL OR valid_until > :today OR valid_until = :noEnd)
                ORDER BY limit_group_id
                """, new MapSqlParameterSource("products", productIds).addValue("today", Date.valueOf(today))
                        .addValue("noEnd", Date.valueOf(BenefitPolicy.NO_END_DATE)),
                (rs, i) -> new Row(rs.getLong("limit_group_id"), rs.getLong("card_product_id"),
                        rs.getString("group_code"), rs.getString("group_name"),
                        BenefitPolicy.Period.valueOf(rs.getString("period_type")),
                        BenefitPolicy.Unit.valueOf(rs.getString("limit_unit")),
                        rs.getObject("base_limit_value", Long.class)));
    }

    record Row(long id, long productId, String groupCode, String name, BenefitPolicy.Period period,
            BenefitPolicy.Unit unit, Long baseLimitValue) {
    }
}
