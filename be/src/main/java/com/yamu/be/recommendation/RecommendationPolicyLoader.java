package com.yamu.be.recommendation;

import com.yamu.be.card.domain.UserCard;
import com.yamu.be.recommendation.domain.*;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 카드 수만큼 반복 조회하지 않고 필요한 ID 집합을 단계별로 조회한다. */
@Component
@RequiredArgsConstructor
class RecommendationPolicyLoader {
    private final CardBenefitRepository benefits;
    private final BenefitTargetRepository targets;
    private final BenefitLimitLinkRepository links;
    private final LimitGroupRepository groups;
    private final TierLimitRepository tiers;
    private final MonthlyLimitUsageRepository monthly;
    private final PaymentLimitUsageRepository daily;
    private final BenefitTargetGroupRepository targetGroups;
    private final BenefitTargetGroupMemberRepository targetMembers;
    private final BenefitApplicationGroupRepository applicationGroups;
    private final BenefitApplicationMemberRepository applicationMembers;
    private final PurchaseItemRepository purchaseItems;

    void validateFallbacks(UserCard card, RecommendationPolicyData data) {
        // 일괄 조회 결과를 카드별로 검증해 다른 상품의 정책 조립은 계속한다.
        for (var benefit : data.benefits()) {
            if (card.getCardProductId().equals(benefit.getCardProductId())
                    && benefit.getId().equals(benefit.getFallbackBenefitId())) {
                throw new IllegalArgumentException("혜택 전환은 자기 자신을 참조할 수 없습니다.");
            }
        }
    }

    RecommendationPolicyData load(long userId, List<UserCard> cards, LocalDate date) {
        if (cards.isEmpty()) {
            return new RecommendationPolicyData(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        var productIds = cards.stream().map(UserCard::getCardProductId).distinct().toList();
        var rows = benefits.findPossiblyEffective(productIds, date, BenefitPolicy.NO_END_DATE);
        var benefitIds = rows.stream().map(CardBenefit::getId).toList();
        var targetRows = benefitIds.isEmpty() ? List.<BenefitTarget>of() : targets.findByCardBenefitIdIn(benefitIds);
        var itemCodes = targetRows.stream().filter(t -> t.getTargetType() == BenefitTarget.Kind.PURCHASE_ITEM)
                .map(BenefitTarget::getTargetValue).distinct().toList();
        var linkRows = benefitIds.isEmpty() ? List.<BenefitLimitLink>of()
                : links.findByCardProductIdInAndCardBenefitIdIn(productIds, benefitIds);
        // 모든 버전을 보존한다. 연결된 버전의 실효성과 같은 코드의 중첩은 입력 구성에서 검사한다.
        var groupRows = groups.findByCardProductIdIn(productIds);
        var linkedIds = linkRows.stream().map(BenefitLimitLink::getLimitGroupId).distinct().toList();
        var tierRows = linkedIds.isEmpty() ? List.<TierLimit>of() : tiers.findByLimitGroupIdIn(linkedIds);
        var monthlyIds = groupRows.stream().filter(g -> linkedIds.contains(g.getId())
                && g.getPeriodType() == BenefitPolicy.Period.MONTHLY).map(LimitGroup::getId).toList();
        var dailyIds = groupRows.stream().filter(g -> linkedIds.contains(g.getId())
                && g.getPeriodType() == BenefitPolicy.Period.DAILY).map(LimitGroup::getId).toList();
        var cardIds = cards.stream().map(UserCard::getId).toList();
        var month = date.withDayOfMonth(1);
        return new RecommendationPolicyData(rows, targetRows, linkRows, groupRows, tierRows,
                monthlyIds.isEmpty() ? List.of()
                        : monthly.findByUserCardIdInAndLimitGroupIdInAndUsageMonth(cardIds, monthlyIds, month),
                dailyIds.isEmpty() ? List.of() : daily.sumDaily(userId, cardIds, dailyIds, date, month),
                benefitIds.isEmpty() ? List.of() : targetGroups.findByCardBenefitIdIn(benefitIds),
                benefitIds.isEmpty() ? List.of() : targetMembers.findByCardBenefitIdIn(benefitIds),
                applicationGroups.findByCardProductIdIn(productIds), applicationMembers.findByCardProductIdIn(productIds), itemCodes.isEmpty() ? List.of() : purchaseItems.findAllById(itemCodes));
    }
}
