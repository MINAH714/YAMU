package com.yamu.be.recommendation.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CardBenefitRepository extends Repository<CardBenefit, Long> {
    /** 불명 날짜도 보존한다. 버전 번호 최댓값으로 현행을 추정하지 않는다. */
    @Query("""
            select b from CardBenefit b where b.cardProductId in :products
            and (b.validFrom is null or b.validFrom <= :date)
            and (b.validUntil is null or b.validUntil > :date or b.validUntil = :noEnd)
            order by b.cardProductId, b.benefitCode, b.policyVersion
            """)
    List<CardBenefit> findPossiblyEffective(@Param("products") Collection<Long> products,
            @Param("date") LocalDate date, @Param("noEnd") LocalDate noEnd);
}
