package com.yamu.be.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitApplicationGroupRepository extends JpaRepository<BenefitApplicationGroup, Long> {
    java.util.List<BenefitApplicationGroup> findByCardProductIdIn(java.util.Collection<Long> ids);
}
