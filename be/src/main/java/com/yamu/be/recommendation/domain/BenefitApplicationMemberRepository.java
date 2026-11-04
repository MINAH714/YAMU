package com.yamu.be.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitApplicationMemberRepository extends JpaRepository<BenefitApplicationMember, BenefitApplicationMember.Key> {
    java.util.List<BenefitApplicationMember> findByCardProductIdIn(java.util.Collection<Long> ids);
}
