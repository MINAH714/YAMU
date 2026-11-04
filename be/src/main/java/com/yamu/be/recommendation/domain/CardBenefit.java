package com.yamu.be.recommendation.domain;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import com.yamu.be.recommendation.BenefitPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 추천에서 기존 테이블을 읽기만 하는 매핑. 참조는 ID로 유지한다. */
@Entity
@Table(name = "card_benefit")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardBenefit extends BaseCreatedTimeEntity {
    @Id
    @Column(name = "card_benefit_id")
    private Long id;
    @Column(name = "card_product_id", nullable = false)
    private Long cardProductId;
    @Column(nullable = false, length = 64)
    private String benefitCode;
    @Column(nullable = false, columnDefinition = "smallint")
    private Integer policyVersion;
    @Column(nullable = false, length = 150)
    private String benefitName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BenefitPolicy.BenefitType benefitType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BenefitPolicy.CalculationType calculationType;
    @Column(precision = 9, scale = 6)
    private BigDecimal rate;
    @Column(precision = 19, scale = 6)
    private BigDecimal fixedValue;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BenefitPolicy.Unit valueUnit;
    @Column(precision = 19, scale = 6)
    private BigDecimal pointKrwRate;
    @Column(length = 32)
    private String applicationTiming;
    private Long fallbackBenefitId;
    private String fallbackMode;
    @Column(length = 16)
    private String roundingMode;
    @Column(columnDefinition = "smallint")
    private Integer roundingUnit;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private BenefitPolicy.RoundingStage roundingStage;
    @Column(nullable = false)
    private Long minPaymentAmount;
    @Column(nullable = false)
    private Long minPerformanceAmount;
    private LocalDate validFrom;
    private LocalDate validUntil;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private String conditionsJson;
}