package com.yamu.be.recommendation.domain;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 추천에서 기존 테이블을 읽기만 하는 매핑. 참조는 ID로 유지한다. */
@Entity
@Table(name = "benefit_limit_link")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenefitLimitLink extends BaseCreatedTimeEntity {
    @Id
    @Column(name = "benefit_limit_link_id")
    private Long id;
    @Column(nullable = false)
    private Long cardProductId;
    @Column(nullable = false)
    private Long cardBenefitId;
    @Column(nullable = false)
    private Long limitGroupId;
}