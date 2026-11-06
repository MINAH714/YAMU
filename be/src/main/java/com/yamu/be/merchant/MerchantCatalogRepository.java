package com.yamu.be.merchant;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 기존 브랜드 단위 merchant를 읽는다. 별도의 브랜드/업종 저장소를 만들지 않는다. */
@Repository
public class MerchantCatalogRepository {
    private final JdbcTemplate jdbc;
    public MerchantCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Merchant(long merchantId, String brandCode, String merchantName, String merchantCategoryCode) { }
    private static final String SELECT = """
            SELECT merchant_id, brand_code, merchant_name, merchant_category_code FROM merchant
            """;
    private static final RowMapper<Merchant> MAPPER = (rs, row) -> new Merchant(rs.getLong("merchant_id"),
            rs.getString("brand_code"), rs.getString("merchant_name"), rs.getString("merchant_category_code"));

    public List<Merchant> findByCategory(String code) {
        return jdbc.query(SELECT + "WHERE merchant_category_code COLLATE utf8mb4_0900_bin = ? "
                + "ORDER BY merchant_name COLLATE utf8mb4_0900_bin, merchant_id", MAPPER, code);
    }
    public Optional<Merchant> findById(long id) {
        return jdbc.query(SELECT + "WHERE merchant_id = ?", MAPPER, id).stream().findFirst();
    }
}
