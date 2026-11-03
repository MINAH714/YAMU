package com.yamu.be.recommendation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PaymentLimitUsageRepository extends Repository<PaymentLimitUsage, Long> {
    /**
     * 이 기준 브랜치에 PaymentTransaction 엔티티가 없어 중복 매핑 대신 이 조인만 native로 읽는다.
     * 실제 한도 차감 기록이 근거이며 결제 상태 문자열을 추정하지 않는다. 복구 완료분은 제외한다.
     * 정상 조회에서 미복구 기록이 없는 카드·그룹은 반환하지 않는다. 입력 구성에서 이를 일 사용량 0으로 해석한다.
     * 조회 실패는 예외로 전달하며 빈 결과로 대체하지 않는다.
     */
    @Query(value = """
            select p.user_card_id as userCardId, u.card_product_id as cardProductId,
                   u.limit_group_id as limitGroupId, sum(u.consumed_value) as usedValue
            from payment_limit_usage u
            join payment_transaction p on p.payment_transaction_id = u.payment_transaction_id
                 and p.card_product_id = u.card_product_id
            join user_card c on c.user_card_id = p.user_card_id
                 and c.user_id = p.user_id and c.card_product_id = u.card_product_id
            where p.user_id = :user and p.user_card_id in :cards and u.limit_group_id in :groups
              and u.usage_date = :date and u.usage_month = :month and u.restored_at is null
            group by p.user_card_id, u.card_product_id, u.limit_group_id
            """, nativeQuery = true)
    List<DailyUsage> sumDaily(@Param("user") long user, @Param("cards") Collection<Long> cards,
            @Param("groups") Collection<Long> groups, @Param("date") LocalDate date,
            @Param("month") LocalDate month);

    interface DailyUsage {
        Long getUserCardId();
        Long getCardProductId();
        Long getLimitGroupId();
        BigDecimal getUsedValue();
    }
}
