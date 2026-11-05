package com.yamu.be.recommendation.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;

@Entity
@Table(name="benefit_application_group")
@Immutable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class BenefitApplicationGroup {
    @Id
    @Column(name="benefit_application_group_id")
    private Long id;
    private Long cardProductId;
    private String groupCode;
    @Column(columnDefinition="smallint")
    private Integer policyVersion;
    private String applicationMode;
}
