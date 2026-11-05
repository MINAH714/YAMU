package com.yamu.be.recommendation.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;

@Entity
@Table(name="benefit_target_group_member")
@Immutable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@IdClass(BenefitTargetGroupMember.Key.class)
public class BenefitTargetGroupMember {
    @Id
    private Long benefitTargetGroupId;
    @Id
    private Long benefitTargetId;
    private Long cardBenefitId;
    @NoArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long benefitTargetGroupId;
        private Long benefitTargetId;
    }
}
