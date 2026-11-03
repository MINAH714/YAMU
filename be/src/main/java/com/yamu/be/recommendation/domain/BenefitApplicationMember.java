package com.yamu.be.recommendation.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;

@Entity
@Table(name="benefit_application_member")
@Immutable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@IdClass(BenefitApplicationMember.Key.class)
public class BenefitApplicationMember {
    @Id
    private Long benefitApplicationGroupId;
    @Id
    private Long cardBenefitId;
    private Long cardProductId;
    @Column(columnDefinition="smallint")
    private Integer priorityOrder;
    @NoArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long benefitApplicationGroupId;
        private Long cardBenefitId;
    }
}
