package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationRows.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.recommendation.domain.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompositionAssemblyTest {
    RecommendationPolicyData data(boolean registerItem, boolean wrongMember) {
        var item=row(BenefitTarget.class,"id",11L,"cardBenefitId",1L,"targetType",BenefitTarget.Kind.PURCHASE_ITEM,
                "targetValue","YOUTUBE_PREMIUM_SUBSCRIPTION");
        var group=row(BenefitTargetGroup.class,"id",12L,"cardBenefitId",1L,"groupCode","APP_STORE_YOUTUBE");
        var member=row(BenefitTargetGroupMember.class,"benefitTargetGroupId",12L,"benefitTargetId",11L,"cardBenefitId",wrongMember?2L:1L);
        var application=row(BenefitApplicationGroup.class,"id",13L,"cardProductId",10L,"groupCode","STACK", "policyVersion",1,"applicationMode","STACK");
        var applicationMember=row(BenefitApplicationMember.class,"benefitApplicationGroupId",13L,"cardProductId",10L,"cardBenefitId",1L);
        return new RecommendationPolicyData(List.of(benefit(1)),List.of(item),List.of(),List.of(),List.of(),List.of(),List.of(),
                List.of(group),List.of(member),List.of(application),List.of(applicationMember),
                registerItem?List.of(row(PurchaseItem.class,"purchaseItemCode","YOUTUBE_PREMIUM_SUBSCRIPTION","active",true)):List.of());
    }
    @Test void realAssemblerConnectsRegisteredTargetsAndApplicationModeWithoutInventingCompleteness() {
        var issues=new ArrayList<RecommendationDataIssue>();
        var policy=new RecommendationInputAssembler(new ObjectMapper()).policy(card(1),data(true,false),LocalDate.of(2026,11,3),400_000L,issues);
        assertThat(policy.complete()).isFalse(); assertThat(policy.additionalCost()).isZero();
        assertThat(policy.benefits().get(0).conditions().targetGroups()).singleElement().satisfies(g->
                assertThat(g.members().get(0).type()).isEqualTo(BenefitPolicy.TargetType.PURCHASE_ITEM));
        assertThat(policy.applicationGroups()).singleElement().satisfies(g->assertThat(g.mode()).isEqualTo(BenefitPolicy.ApplicationMode.STACK));
        assertThat(issues).noneMatch(i->i.code().equals("BENEFIT_COMBINATIONS_UNKNOWN"));
        assertThat(issues).anyMatch(i->i.code().equals("POLICY_COMPLETENESS_UNVERIFIED"));
    }
    @Test void rejectsCrossBenefitAndUnregisteredPurchaseItemReferences() {
        var assembler=new RecommendationInputAssembler(new ObjectMapper());
        assertThatThrownBy(()->assembler.policy(card(1),data(true,true),LocalDate.of(2026,11,3),400_000L,new ArrayList<>())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->assembler.policy(card(1),data(false,false),LocalDate.of(2026,11,3),400_000L,new ArrayList<>())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyTargetGroupAndConditionMemberAreInvalid() {
        var emptyData=data(true,false);
        setField(emptyData.targetMembers().get(0),"benefitTargetGroupId",99L);
        assertThatThrownBy(()->new RecommendationInputAssembler(new ObjectMapper()).policy(card(1),emptyData,LocalDate.of(2026,11,3),0L,new ArrayList<>())).isInstanceOf(IllegalArgumentException.class);
        var data=data(true,false);
        setField(data.targets().get(0),"targetType",BenefitTarget.Kind.CONDITION);
        var conditionData=data;
        assertThatThrownBy(()->new RecommendationInputAssembler(new ObjectMapper()).policy(card(1),conditionData,LocalDate.of(2026,11,3),0L,new ArrayList<>())).isInstanceOf(IllegalArgumentException.class);
    }
}
