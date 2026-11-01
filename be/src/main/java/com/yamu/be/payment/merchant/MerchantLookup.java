package com.yamu.be.payment.merchant;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제에 필요한 가맹점(브랜드) 정보를 merchant에서 직접 읽는다.
 *
 * <p>MERCHANT는 브랜드마다 한 행이다(10/26 결정). 가맹점·업종 엔티티는 기준 데이터 담당 영역이라 SQL로만 조회한다.
 */
@Component
public class MerchantLookup {

    private static final String SELECT = """
            select merchant_id, merchant_name, brand_code, merchant_category_code
            from merchant
            """;

    private final JdbcTemplate jdbc;

    public MerchantLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Merchant> findByBrandCode(String brandCode) {
        return queryOne(SELECT + "where brand_code = ?", brandCode);
    }

    public Optional<Merchant> findById(Long merchantId) {
        return queryOne(SELECT + "where merchant_id = ?", merchantId);
    }

    private Optional<Merchant> queryOne(String sql, Object arg) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, (rs, i) -> new Merchant(
                    rs.getLong("merchant_id"),
                    rs.getString("merchant_name"),
                    rs.getString("brand_code"),
                    rs.getString("merchant_category_code")), arg));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 브랜드 단위 가맹점.
     *
     * @param categoryCode 가맹점 업종 코드(POL-004). 혜택 대상 판정과 앱 표시에 쓴다
     */
    public record Merchant(Long merchantId, String name, String brandCode, String categoryCode) {}
}
