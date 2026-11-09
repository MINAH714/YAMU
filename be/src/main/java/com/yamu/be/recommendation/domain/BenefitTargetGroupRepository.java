package com.yamu.be.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitTargetGroupRepository extends JpaRepository<BenefitTargetGroup, Long> {
    java.util.List<BenefitTargetGroup> findByCardBenefitIdIn(java.util.Collection<Long> ids);
}
