package com.yamu.be.recommendation.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;
import com.yamu.be.global.entity.BaseCreatedTimeEntity;

@Entity
@Table(name="benefit_target_group")
@Immutable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class BenefitTargetGroup extends BaseCreatedTimeEntity {
    @Id
    @Column(name="benefit_target_group_id")
    private Long id;
    private Long cardBenefitId;
    private String groupCode;
    @Column(name="is_excluded")
    private boolean excluded;
}
