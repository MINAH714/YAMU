package com.yamu.be.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitTargetGroupMemberRepository extends JpaRepository<BenefitTargetGroupMember, BenefitTargetGroupMember.Key> {
    java.util.List<BenefitTargetGroupMember> findByCardBenefitIdIn(java.util.Collection<Long> ids);
}
