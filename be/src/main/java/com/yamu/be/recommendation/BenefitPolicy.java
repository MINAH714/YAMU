package com.yamu.be.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 확인된 상품 정책의 계산 입력. DB 엔티티와 분리하며 미확인 숫자를 기본값으로 채우지 않는다.
 * complete는 해당 거래에 영향을 주는 혜택·조합·제외 조건을 모두 확인했다는 공급 측의 보증이다.
 */
public record BenefitPolicy(boolean complete, Long previousMonthPerformance,
                            BigDecimal additionalCost, List<Benefit> benefits,
                            List<Limit> limits, List<List<Long>> allowedCombinations,
                            List<ApplicationGroup> applicationGroups, java.util.Map<Long, Fallback> fallbacks) {
    public BenefitPolicy(boolean complete, Long previousMonthPerformance, BigDecimal additionalCost,
            List<Benefit> benefits, List<Limit> limits, List<List<Long>> allowedCombinations) {
        this(complete, previousMonthPerformance, additionalCost, benefits, limits, allowedCombinations, null, java.util.Map.of());
    }
    /** 종료 제한이 없음을 확인한 경우에만 공급하는 값. NULL 날짜의 기본값으로 사용하지 않는다. */
    public static final LocalDate NO_END_DATE = LocalDate.of(9999, 12, 31);
    public BenefitPolicy {
        if (previousMonthPerformance != null && previousMonthPerformance < 0) {
            throw new IllegalArgumentException("전월 실적은 음수일 수 없습니다.");
        }
        if (additionalCost != null && additionalCost.signum() < 0) {
            throw new IllegalArgumentException("추가 비용은 음수일 수 없습니다.");
        }
        benefits = List.copyOf(benefits);
        limits = List.copyOf(limits);
        applicationGroups = applicationGroups == null ? null : List.copyOf(applicationGroups);
        fallbacks = java.util.Map.copyOf(fallbacks);
        if (allowedCombinations != null) {
            allowedCombinations = allowedCombinations.stream().map(List::copyOf).toList();
        }
        if (benefits.stream().map(Benefit::id).distinct().count() != benefits.size()
                || limits.stream().map(Limit::id).distinct().count() != limits.size()) {
            throw new IllegalArgumentException("혜택·한도 ID를 중복 전달할 수 없습니다.");
        }
        if (allowedCombinations != null) {
            Set<Long> benefitIds = benefits.stream().map(Benefit::id).collect(java.util.stream.Collectors.toSet());
            for (List<Long> combination : allowedCombinations) {
                if (combination.stream().distinct().count() != combination.size()
                        || !benefitIds.containsAll(combination)) {
                    throw new IllegalArgumentException("혜택 조합은 중복 없이 존재하는 혜택을 참조해야 합니다.");
                }
            }
        }
    }

    public enum BenefitType { DISCOUNT, CASHBACK, POINT }
    public enum Unit { KRW, POINT, COUNT }
    public enum CalculationType { RATE, FIXED }
    public enum CalculationOrder { ROUND_THEN_LIMIT, LIMIT_THEN_ROUND }
    public enum RoundingStage { PER_TRANSACTION, AFTER_AGGREGATION }
    public enum Period { PER_TRANSACTION, DAILY, MONTHLY }
    public enum CountMode { PER_BENEFIT, PER_TRANSACTION }
    public enum TargetType { ALL, BRAND, CATEGORY, PURCHASE_ITEM }
    public enum ApplicationMode { STACK, MAX_ONE, PRIORITY_ONE }
    public record ApplicationMember(long benefitId, Integer priorityOrder) { }
    public record ApplicationGroup(long id, ApplicationMode mode, List<ApplicationMember> members) {
        public ApplicationGroup {
            Objects.requireNonNull(mode);
            members = List.copyOf(members);
            if (members.isEmpty() || members.stream().map(ApplicationMember::benefitId).distinct().count() != members.size()
                    || members.stream().anyMatch(m -> m.benefitId() <= 0 || (mode == ApplicationMode.PRIORITY_ONE
                        ? m.priorityOrder() == null || m.priorityOrder() <= 0 : m.priorityOrder() != null))) {
                throw new IllegalArgumentException("혜택 적용 그룹 구성 오류");
            }
        }
    }
    public record Fallback(long benefitId, String mode) {
        public Fallback {
            if (benefitId <= 0 || !"EXCESS_PAYMENT_AMOUNT".equals(mode)) throw new IllegalArgumentException("전환 정책 오류");
        }
    }
    public record TargetGroup(long id, boolean excluded, List<Target> members) {
        public TargetGroup {
            members = List.copyOf(members);
            if (members.isEmpty() || members.stream().anyMatch(Target::excluded)) throw new IllegalArgumentException("대상 그룹 구성 오류");
        }
    }

    /** baseAmount는 상품 정책에 따른 계산 대상액. 포인트 사용분 제외 등의 사실 미확인은 null이다. */
    public record Benefit(long id, int version, String name, BenefitType type,
                          CalculationType calculationType, BigDecimal rateOrFixedValue,
                          Unit unit, BigDecimal pointKrwRate, Long baseAmount,
                          Long minimumPayment, Long minimumPerformance,
                          Conditions conditions, Rounding rounding, CalculationOrder calculationOrder,
                          List<Long> limitIds) {
        public Benefit {
            if (id <= 0 || version <= 0) {
                throw new IllegalArgumentException("혜택 ID·버전은 양수여야 합니다.");
            }
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(calculationType);
            Objects.requireNonNull(unit);
            if (unit == Unit.COUNT || (type == BenefitType.POINT) != (unit == Unit.POINT)) {
                throw new IllegalArgumentException("혜택 종류와 금액 단위가 맞지 않습니다.");
            }
            if ((rateOrFixedValue != null && (rateOrFixedValue.signum() < 0
                    || (calculationType == CalculationType.RATE && rateOrFixedValue.compareTo(BigDecimal.ONE) > 0)))
                    || (pointKrwRate != null && pointKrwRate.signum() <= 0)
                    || (baseAmount != null && baseAmount < 0)
                    || (minimumPayment != null && minimumPayment < 0)
                    || (minimumPerformance != null && minimumPerformance < 0)) {
                throw new IllegalArgumentException("혜택 계산 값의 범위가 잘못되었습니다.");
            }
            limitIds = List.copyOf(limitIds);
            if (limitIds.stream().distinct().count() != limitIds.size()) {
                throw new IllegalArgumentException("혜택에 같은 한도를 중복 연결할 수 없습니다.");
            }
        }
    }

    /** 합산 후 처리에는 같은 정책·집계 범위의 처리 전 원시 합계가 필요하다. */
    public record Rounding(RoundingMode mode, BigDecimal unit, RoundingStage stage,
                           BigDecimal aggregateRawBefore) {
        public Rounding {
            if (mode != null && mode != RoundingMode.DOWN && mode != RoundingMode.HALF_UP
                    && mode != RoundingMode.HALF_EVEN && mode != RoundingMode.UP) {
                throw new IllegalArgumentException("지원하지 않는 소수점 처리 방식입니다.");
            }
            if ((unit != null && unit.signum() <= 0)
                    || (aggregateRawBefore != null && aggregateRawBefore.signum() < 0)) {
                throw new IllegalArgumentException("소수점 처리 단위·합계가 잘못되었습니다.");
            }
        }
    }

    /** null 한도·사용량은 미확인. 무제한은 확인된 정책에서 limitIds에 연결하지 않아 표현한다. */
    public record Limit(long id, int version, Unit unit, Period period, Long maximum, Long used,
                        CountMode countMode, Boolean consumeOnZero) {
        public Limit {
            if (id <= 0 || version <= 0 || (maximum != null && maximum < 0) || (used != null && used < 0)) {
                throw new IllegalArgumentException("한도 ID·버전·금액이 잘못되었습니다.");
            }
            Objects.requireNonNull(unit);
            Objects.requireNonNull(period);
            if (period == Period.PER_TRANSACTION && used != null && used != 0) {
                throw new IllegalArgumentException("건별 한도에는 과거 사용량을 합산하지 않습니다.");
            }
        }
    }

    /**
     * targetGroups는 경로 내 AND·경로 간 OR다. null이면 기존 평면 대상 OR 입력의 호환 경로다.
     * requiredFacts AND, 제외 경로·excludedFacts OR. facts 키 부재는 미확인이다.
     * 시작일·종료일 NULL은 미확인이다. 종료 제한이 없음이 확인된 경우만 NO_END_DATE를 전달한다.
     * 일반 종료일은 미포함이며 verified/currentVersionVerified로 누락 날짜를 대신하지 않는다.
     */
    public record Conditions(boolean verified, boolean currentVersionVerified,
                             LocalDate validFrom, LocalDate validUntil,
                             List<Target> targets, Set<String> requiredFacts,
                             Set<String> excludedFacts, Schedule schedule, List<TargetGroup> targetGroups) {
        public Conditions(boolean verified, boolean currentVersionVerified, LocalDate validFrom, LocalDate validUntil,
                List<Target> targets, Set<String> requiredFacts, Set<String> excludedFacts, Schedule schedule) {
            this(verified, currentVersionVerified, validFrom, validUntil, targets, requiredFacts, excludedFacts, schedule, null);
        }
        public Conditions {
            targetGroups = targetGroups == null ? null : List.copyOf(targetGroups);
            targets = List.copyOf(targets);
            requiredFacts = Set.copyOf(requiredFacts);
            excludedFacts = Set.copyOf(excludedFacts);
            if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException("약관 종료일은 시작일 뒤여야 합니다.");
            }
        }
    }

    public record Target(TargetType type, Long merchantId, String categoryCode, boolean excluded) {
        public Target {
            Objects.requireNonNull(type);
            if ((type == TargetType.BRAND && (merchantId == null || merchantId <= 0 || categoryCode != null))
                    || ((type == TargetType.CATEGORY || type == TargetType.PURCHASE_ITEM)
                    && (categoryCode == null || categoryCode.isBlank() || merchantId != null))
                    || (type == TargetType.ALL && (merchantId != null || categoryCode != null))) {
                throw new IllegalArgumentException("혜택 대상 형식이 잘못되었습니다.");
            }
        }
    }

    /** 빈 요일 집합과 없는 두 시각은 확인된 제한 없음. Schedule 자체의 null은 미확인이다. */
    public record Schedule(Set<DayOfWeek> weekdays, LocalTime startTime, LocalTime endTime) {
        public Schedule {
            weekdays = Set.copyOf(weekdays);
            if ((startTime == null) != (endTime == null)
                    || (startTime != null && (startTime.equals(endTime)
                    || startTime.getSecond() != 0 || endTime.getSecond() != 0
                    || startTime.getNano() != 0 || endTime.getNano() != 0))) {
                throw new IllegalArgumentException("시간 조건은 서로 다른 HH:mm 두 시각으로 전달해야 합니다.");
            }
        }

        public static Schedule unrestricted() {
            return new Schedule(Set.of(), null, null);
        }
    }
}
