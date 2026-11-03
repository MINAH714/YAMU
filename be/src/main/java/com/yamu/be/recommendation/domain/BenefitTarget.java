package com.yamu.be.recommendation.domain;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 추천에서 기존 테이블을 읽기만 하는 매핑. 참조는 ID로 유지한다. */
@Entity
@Table(name = "benefit_target")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenefitTarget extends BaseCreatedTimeEntity {
    @Id
    @Column(name = "benefit_target_id")
    private Long id;
    public enum Kind { ALL, BRAND, CATEGORY, CONDITION }
    @Column(name = "card_benefit_id", nullable = false)
    private Long cardBenefitId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind targetType;
    private Long merchantId;
    @Column(length = 128)
    private String targetValue;
    @Column(name = "is_excluded", nullable = false)
    private boolean excluded;
}