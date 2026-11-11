package com.yamu.be.home;

import com.yamu.be.merchant.MerchantCategories;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 홈에만 쓰는 작은 조회. 결제·알림·계좌는 다른 도메인이라 엔티티를 끌어오지 않고 필요한 칸만 SQL로 읽는다.
 * 가맹점 이름·업종은 결제 내역(PaymentHistoryReader)과 같이 결제 당시 스냅샷을 먼저 본다.
 */
@Component
class HomeReader {

    private static final String MERCHANT_NAME =
            "coalesce(nullif(p.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)";
    private static final String CATEGORY_CODE =
            "coalesce(nullif(p.merchant_context_snapshot ->> '$.categoryCode', 'null'), m.merchant_category_code)";
    /** 저장된 추천 결과의 1순위 카드. 결과가 없는 결제(SEED·옛 결제)는 null */
    private static final String TOP_CARD = "cast(json_unquote(json_extract(p.recommendation_result_json,"
            + " '$.candidates[0].card.userCardId')) as unsigned)";
    private static final String TOP_NET_BENEFIT = "cast(json_unquote(json_extract(p.recommendation_result_json,"
            + " '$.candidates[0].netBenefit')) as decimal(19, 4))";

    private final NamedParameterJdbcTemplate jdbc;

    HomeReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int unreadNotificationCount(long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification where user_id = :userId and read_at is null",
                new MapSqlParameterSource("userId", userId), Integer.class);
        return count == null ? 0 : count;
    }

    /** 연결(CONNECTED) 계좌가 하나라도 있는지. */
    boolean hasConnectedAccount(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from linked_account where user_id = :userId and status = 'CONNECTED')",
                new MapSqlParameterSource("userId", userId), Boolean.class));
    }

    /** 요청 시각이 가장 늦은 승인 결제 하나(결제 내역 기본 정렬과 같다). 취소·거절은 뺀다. */
    Optional<HomeResponse.RecentPayment> recentPayment(long userId) {
        List<HomeResponse.RecentPayment> rows = jdbc.query("""
                select p.payment_transaction_id, %s as merchant_name, p.approved_amount,
                       coalesce(c.card_alias, cp.product_name) as card_name,
                       (select cast(sum(b.krw_amount) as signed) from payment_benefit b
                         where b.payment_transaction_id = p.payment_transaction_id) as benefit_amount,
                       p.approved_at
                from payment_transaction p
                join merchant m on m.merchant_id = p.merchant_id
                left join user_card c on c.user_card_id = p.user_card_id
                left join card_product cp on cp.card_product_id = p.card_product_id
                where p.user_id = :userId and p.status = 'APPROVED'
                order by p.requested_at desc, p.payment_transaction_id desc
                limit 1
                """.formatted(MERCHANT_NAME), new MapSqlParameterSource("userId", userId),
                (rs, i) -> new HomeResponse.RecentPayment(rs.getLong("payment_transaction_id"),
                        rs.getString("merchant_name"), rs.getLong("approved_amount"), rs.getString("card_name"),
                        rs.getObject("benefit_amount", Long.class),
                        rs.getObject("approved_at", LocalDateTime.class)));
        return rows.stream().findFirst();
    }

    /**
     * 오늘 쓸 카드(2026-11-10 결정). {@code since} 뒤 승인 결제 중 건수가 가장 많은 업종을 고르고(같으면 최근에 쓴 업종),
     * 그 업종 결제들의 저장된 추천 결과에서 1순위로 가장 많이 나온 카드를 고른다(같으면 최근에 1순위였던 카드).
     * 추천 엔진을 새로 부르지 않는다.
     *
     * <ul>
     *   <li>1순위 순혜택이 0원인 결과는 세지 않는다("혜택이 가장 커요"라고 말할 근거가 없다)</li>
     *   <li>지금 쓸 수 있는(ACTIVE) 내 카드만 고른다. 해제·정지한 카드가 1순위였던 결과는 세지 않는다</li>
     *   <li>가장 많이 쓴 업종 결제에 추천 결과가 하나도 없으면(SEED 결제뿐) 다른 업종으로 넘어가지 않고 empty다</li>
     * </ul>
     */
    Optional<HomeResponse.TodayCard> todayCard(long userId, LocalDateTime since) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("since", since);
        List<String> categories = jdbc.queryForList("""
                select %s as category_code
                from payment_transaction p
                join merchant m on m.merchant_id = p.merchant_id
                where p.user_id = :userId and p.status = 'APPROVED' and p.requested_at >= :since
                group by category_code
                having category_code is not null
                order by count(*) desc, max(p.requested_at) desc
                limit 1
                """.formatted(CATEGORY_CODE), params, String.class);
        if (categories.isEmpty()) {
            return Optional.empty();
        }
        String category = categories.get(0);
        List<HomeResponse.TodayCard> cards = jdbc.query("""
                select c.user_card_id, coalesce(c.card_alias, cp.product_name) as card_name
                from payment_transaction p
                join merchant m on m.merchant_id = p.merchant_id
                join user_card c on c.user_card_id = %s and c.user_id = :userId and c.status = 'ACTIVE'
                join card_product cp on cp.card_product_id = c.card_product_id
                where p.user_id = :userId and p.status = 'APPROVED' and p.requested_at >= :since
                  and %s = :category and %s > 0
                group by c.user_card_id, card_name
                order by count(*) desc, max(p.requested_at) desc
                limit 1
                """.formatted(TOP_CARD, CATEGORY_CODE, TOP_NET_BENEFIT), params.addValue("category", category),
                (rs, i) -> new HomeResponse.TodayCard(rs.getLong("user_card_id"), rs.getString("card_name"),
                        benefitLine(category)));
        return cards.stream().findFirst();
    }

    private static String benefitLine(String categoryCode) {
        return MerchantCategories.list().stream()
                .filter(c -> c.merchantCategoryCode().equals(categoryCode))
                .findFirst()
                .map(c -> c.merchantCategoryName() + " 결제에 혜택이 가장 커요")
                .orElse("자주 쓰는 곳에서 혜택이 가장 커요");
    }
}
