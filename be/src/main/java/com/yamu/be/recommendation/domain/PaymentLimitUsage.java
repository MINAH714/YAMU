package com.yamu.be.recommendation.domain;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 추천에서 기존 테이블을 읽기만 하는 매핑. 참조는 ID로 유지한다. */
@Entity
@Table(name = "payment_limit_usage")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLimitUsage extends BaseTimeEntity {
    @Id
    @Column(name = "payment_limit_usage_id")
    private Long id;
    @Column(nullable = false)
    private Long paymentTransactionId;
    @Column(nullable = false)
    private Long cardProductId;
    @Column(nullable = false)
    private Long limitGroupId;
    @Column(nullable = false)
    private Long remainingBefore;
    @Column(nullable = false)
    private Long consumedValue;
    @Column(nullable = false)
    private LocalDate usageDate;
    @Column(nullable = false)
    private LocalDate usageMonth;
    private java.time.LocalDateTime restoredAt;
    @Version
    private Long version;
}