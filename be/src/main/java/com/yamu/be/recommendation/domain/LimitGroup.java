package com.yamu.be.recommendation.domain;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import com.yamu.be.recommendation.BenefitPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 추천에서 기존 테이블을 읽기만 하는 매핑. 참조는 ID로 유지한다. */
@Entity
@Table(name = "limit_group")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LimitGroup extends BaseCreatedTimeEntity {
    @Id
    @Column(name = "limit_group_id")
    private Long id;
    @Column(nullable = false)
    private Long cardProductId;
    @Column(nullable = false, length = 64)
    private String groupCode;
    @Column(nullable = false, columnDefinition = "smallint")
    private Integer policyVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BenefitPolicy.Period periodType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BenefitPolicy.Unit limitUnit;
    private Long baseLimitValue;
    private LocalDate validFrom;
    private LocalDate validUntil;
}