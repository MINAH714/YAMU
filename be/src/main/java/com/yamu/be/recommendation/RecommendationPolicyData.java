package com.yamu.be.recommendation;

import com.yamu.be.recommendation.domain.*;
import java.util.List;

/** 한 번의 일괄 조회 결과. 저장값과 정책의 확인 여부는 별개다. */
record RecommendationPolicyData(List<CardBenefit> benefits, List<BenefitTarget> targets,
                                List<BenefitLimitLink> links, List<LimitGroup> groups, List<TierLimit> tiers,
                                List<MonthlyLimitUsage> monthly, List<PaymentLimitUsageRepository.DailyUsage> daily,
                                List<BenefitTargetGroup> targetGroups, List<BenefitTargetGroupMember> targetMembers,
                                List<BenefitApplicationGroup> applicationGroups, List<BenefitApplicationMember> applicationMembers, List<PurchaseItem> purchaseItems) {
    RecommendationPolicyData(List<CardBenefit> benefits, List<BenefitTarget> targets, List<BenefitLimitLink> links,
            List<LimitGroup> groups, List<TierLimit> tiers, List<MonthlyLimitUsage> monthly, List<PaymentLimitUsageRepository.DailyUsage> daily) {
        this(benefits, targets, links, groups, tiers, monthly, daily, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
