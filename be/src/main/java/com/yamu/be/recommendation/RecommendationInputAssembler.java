package com.yamu.be.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.recommendation.BenefitPolicy.*;
import com.yamu.be.recommendation.domain.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 저장된 필드만 변환한다. 정책 조사 완료·계산 순서·조합·인정 규칙을 발명하지 않는다. */
@Component
@RequiredArgsConstructor
class RecommendationInputAssembler {
    private final ObjectMapper json;

    BenefitPolicy policy(UserCard card, RecommendationPolicyData data, LocalDate date, Long previous,
                         List<RecommendationDataIssue> issues) {
        var rows = data.benefits().stream().filter(b -> b.getCardProductId().equals(card.getCardProductId())).toList();
        if (rows.isEmpty()) {
            issue(issues, card, null, "POLICY_NOT_REGISTERED_FOR_TRANSACTION");
        }
        // DB에는 조사 완료·빠진 혜택/제외 조건의 유무를 보증하는 필드가 없다.
        issue(issues, card, null, "POLICY_COMPLETENESS_UNVERIFIED");
        var codes = new HashSet<String>();
        var converted = new ArrayList<Benefit>();
        var groupIds = new LinkedHashSet<Long>();
        for (var row : rows) {
            if (!codes.add(row.getBenefitCode())) {
                issue(issues, card, row.getId(), "BENEFIT_VERSION_AMBIGUOUS");
            }
            if (!effective(row.getValidFrom(), row.getValidUntil(), date)) {
                issue(issues, card, row.getId(), "BENEFIT_VALIDITY_UNKNOWN");
            }
            var links = data.links().stream().filter(l -> l.getCardProductId().equals(card.getCardProductId())
                    && l.getCardBenefitId().equals(row.getId())).map(BenefitLimitLink::getLimitGroupId).distinct().toList();
            groupIds.addAll(links);
            var targets = new ArrayList<Target>();
            var required = new HashSet<String>();
            var excluded = new HashSet<String>();
            for (var target : data.targets()) {
                if (!target.getCardBenefitId().equals(row.getId())) {
                    continue;
                }
                if (target.getTargetType() == BenefitTarget.Kind.CONDITION) {
                    (target.isExcluded() ? excluded : required).add(target.getTargetValue());
                } else {
                    targets.add(new Target(TargetType.valueOf(target.getTargetType().name()), target.getMerchantId(),
                            target.getTargetValue(), target.isExcluded()));
                }
            }
            Schedule schedule;
            try {
                schedule = schedule(row.getConditionsJson());
            } catch (IllegalArgumentException e) {
                schedule = null;
                issue(issues, card, row.getId(), "SCHEDULE_INVALID");
            }
            // false는 조건 없음이 아니라 약관의 완전성/현행 확인 근거가 공급되지 않았음을 뜻한다.
            var conditions = new Conditions(false, false, row.getValidFrom(), row.getValidUntil(), targets,
                    required, excluded, schedule, targetGroups(row, data));
            var rounding = new Rounding(row.getRoundingMode() == null ? null : RoundingMode.valueOf(row.getRoundingMode()),
                    row.getRoundingUnit() == null ? null : BigDecimal.valueOf(row.getRoundingUnit()),
                    row.getRoundingStage(), null);
            if (row.getRoundingStage() == RoundingStage.AFTER_AGGREGATION) {
                issue(issues, card, row.getId(), "RAW_AGGREGATION_SCOPE_AND_VALUE_UNKNOWN");
            }
            converted.add(new Benefit(row.getId(), row.getPolicyVersion(), row.getBenefitName(), row.getBenefitType(),
                    row.getCalculationType(), row.getCalculationType() == CalculationType.RATE ? row.getRate() : row.getFixedValue(),
                    row.getValueUnit(), row.getPointKrwRate(), null,
                    // DEFAULT 0만으로 확인된 무조건 정책임을 보증하지 않는다. complete=false를 유지한다.
                    row.getMinPaymentAmount(), row.getMinPerformanceAmount(), conditions, rounding, null, links));
        }
        var limits = new ArrayList<Limit>();
        for (Long groupId : groupIds) {
            var group = data.groups().stream().filter(g -> g.getId().equals(groupId)
                    && g.getCardProductId().equals(card.getCardProductId())).findFirst().orElse(null);
            if (group == null) {
                issue(issues, card, groupId, "LINKED_LIMIT_MISSING");
                continue;
            }
            boolean valid = effective(group.getValidFrom(), group.getValidUntil(), date);
            long versions = data.groups().stream().filter(g -> g.getCardProductId().equals(card.getCardProductId())
                    && g.getGroupCode().equals(group.getGroupCode())
                    && possiblyEffective(g.getValidFrom(), g.getValidUntil(), date)).count();
            if (!valid || versions != 1) {
                issue(issues, card, groupId, "LIMIT_VERSION_OR_VALIDITY_UNKNOWN");
            }
            Long maximum = valid && versions == 1 ? maximum(group, data.tiers(), previous) : null;
            if (maximum == null) {
                issue(issues, card, groupId, "LIMIT_MAXIMUM_UNKNOWN");
            }
            Long used = used(card, group, data);
            if (used == null) {
                issue(issues, card, groupId, "LIMIT_USAGE_MISSING");
            }
            if (group.getLimitUnit() == Unit.COUNT) {
                issue(issues, card, groupId, "COUNT_CONSUMPTION_POLICY_UNKNOWN");
            }
            limits.add(new Limit(groupId, group.getPolicyVersion(), group.getLimitUnit(), group.getPeriodType(),
                    maximum, used, null, null));
        }
        // POL-012·013: 현재 지원하는 일시불·YAMU 모의 무이자 할부의 금융 수수료·이자는 0원이다.
        // 비용 0원과 다른 상품 정책의 확인 여부는 별개이므로 complete=false를 유지한다.
        var applications = applicationGroups(card, data, rows);
        var fallbacks = new HashMap<Long, Fallback>();
        for (var row : rows) {
            if ((row.getFallbackBenefitId() == null) != (row.getFallbackMode() == null)) {
                throw new IllegalArgumentException("전환 정책 필드 누락");
            }
            if (row.getFallbackBenefitId() != null) {
                var destination = rows.stream().filter(b -> b.getId().equals(row.getFallbackBenefitId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("전환 혜택 누락"));
                if (destination == row || destination.getFallbackBenefitId() != null
                        || row.getCalculationType() != CalculationType.RATE || destination.getCalculationType() != CalculationType.RATE
                        || row.getRate() == null || row.getRate().signum() <= 0) {
                    throw new IllegalArgumentException("순환·연쇄·정액 전환 불가");
                }
                fallbacks.put(row.getId(), new Fallback(destination.getId(), row.getFallbackMode()));
            }
        }
        if (applications.isEmpty()) issue(issues, card, null, "BENEFIT_COMBINATIONS_UNKNOWN");
        return new BenefitPolicy(false, previous, BigDecimal.ZERO, converted, limits, null, applications, fallbacks);
    }

    private List<TargetGroup> targetGroups(CardBenefit benefit, RecommendationPolicyData data) {
        if (data.targetGroups().stream().noneMatch(g -> g.getCardBenefitId().equals(benefit.getId()))) {
            if (data.targets().stream().anyMatch(t -> t.getCardBenefitId().equals(benefit.getId()) && t.getTargetType() != BenefitTarget.Kind.CONDITION)) {
                throw new IllegalArgumentException("그룹에 연결되지 않은 일반 대상");
            }
            return List.of();
        }
        var targets = data.targets().stream().filter(t -> t.getCardBenefitId().equals(benefit.getId()))
                .collect(java.util.stream.Collectors.toMap(BenefitTarget::getId, t -> t));
        var connected = new HashSet<Long>();
        var result = new ArrayList<TargetGroup>();
        for (var group : data.targetGroups()) {
            if (!group.getCardBenefitId().equals(benefit.getId())) continue;
            var members = new ArrayList<Target>();
            for (var member : data.targetMembers()) {
                if (!member.getBenefitTargetGroupId().equals(group.getId())) continue;
                var target = targets.get(member.getBenefitTargetId());
                if (!member.getCardBenefitId().equals(benefit.getId()) || target == null
                        || target.getTargetType() == BenefitTarget.Kind.CONDITION || target.isExcluded()) {
                    throw new IllegalArgumentException("다른 혜택/공통 조건은 대상 그룹에 연결할 수 없습니다.");
                }
                if (target.getTargetType() == BenefitTarget.Kind.PURCHASE_ITEM && data.purchaseItems().stream()
                        .noneMatch(item -> item.getPurchaseItemCode().equals(target.getTargetValue()))) {
                    throw new IllegalArgumentException("등록되지 않은 정책 구매 항목");
                }
                connected.add(target.getId());
                members.add(new Target(TargetType.valueOf(target.getTargetType().name()), target.getMerchantId(), target.getTargetValue(), false));
            }
            result.add(new TargetGroup(group.getId(), group.isExcluded(), members));
        }
        if (!result.isEmpty() && targets.values().stream().anyMatch(t -> t.getTargetType() != BenefitTarget.Kind.CONDITION
                && !connected.contains(t.getId()))) throw new IllegalArgumentException("연결되지 않은 대상");
        return result;
    }

    private List<ApplicationGroup> applicationGroups(UserCard card, RecommendationPolicyData data, List<CardBenefit> benefits) {
        var ids = benefits.stream().map(CardBenefit::getId).collect(java.util.stream.Collectors.toSet());
        var attached = new HashSet<Long>();
        var codes = new HashSet<String>();
        var result = new ArrayList<ApplicationGroup>();
        for (var group : data.applicationGroups()) {
            if (!group.getCardProductId().equals(card.getCardProductId())) continue;
            var members = new ArrayList<ApplicationMember>();
            for (var member : data.applicationMembers()) {
                if (!member.getBenefitApplicationGroupId().equals(group.getId())) continue;
                if (!member.getCardProductId().equals(card.getCardProductId())) throw new IllegalArgumentException("다른 상품 연결");
                if (!ids.contains(member.getCardBenefitId())) continue;
                if (!attached.add(member.getCardBenefitId())) throw new IllegalArgumentException("혜택 적용 그룹 중복");
                members.add(new ApplicationMember(member.getCardBenefitId(), member.getPriorityOrder()));
            }
            if (!members.isEmpty()) {
                if (!codes.add(group.getGroupCode())) throw new IllegalArgumentException("적용 그룹 버전 중첩");
                result.add(new ApplicationGroup(group.getId(), ApplicationMode.valueOf(group.getApplicationMode()), members));
            }
        }
        return result;
    }

    private Long maximum(LimitGroup group, List<TierLimit> tiers, Long previous) {
        var rows = tiers.stream().filter(t -> t.getLimitGroupId().equals(group.getId())).toList();
        if (rows.isEmpty()) {
            return group.getBaseLimitValue();
        }
        // 기본 한도와 구간 한도가 공존할 때 우선순위를 가정하지 않는다.
        if (previous == null || group.getBaseLimitValue() != null) {
            return null;
        }
        var sorted = rows.stream().sorted(Comparator.comparing(TierLimit::getMinPerformanceAmount)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            Long end = sorted.get(i - 1).getMaxPerformanceAmount();
            if (end == null || end > sorted.get(i).getMinPerformanceAmount()) {
                return null;
            }
        }
        var matched = rows.stream().filter(t -> previous >= t.getMinPerformanceAmount()
                && (t.getMaxPerformanceAmount() == null || previous < t.getMaxPerformanceAmount())).toList();
        return matched.size() == 1 ? matched.get(0).getLimitValue() : null;
    }

    private Long used(UserCard card, LimitGroup group, RecommendationPolicyData data) {
        if (group.getPeriodType() == Period.PER_TRANSACTION) {
            return 0L; // 건별 한도는 과거 사용량을 이월하지 않는 엔진의 기존 의미다.
        }
        if (group.getPeriodType() == Period.MONTHLY) {
            return data.monthly().stream().filter(u -> u.getUserCardId().equals(card.getId())
                    && u.getCardProductId().equals(card.getCardProductId()) && u.getLimitGroupId().equals(group.getId()))
                    .map(MonthlyLimitUsage::getUsedValue).findFirst().orElse(null);
        }
        // Loader의 일별 조회가 정상 완료된 결과다. 미복구 기록이 없으면 미사용/전부 복구된 0이다.
        // 조회 실패는 Loader에서 예외로 전달되므로 이 경로에 빈 결과로 들어오지 않는다.
        return data.daily().stream().filter(u -> u.getUserCardId().equals(card.getId())
                && u.getCardProductId().equals(card.getCardProductId()) && u.getLimitGroupId().equals(group.getId()))
                .map(u -> u.getUsedValue().longValueExact()).findFirst().orElse(0L);
    }

    static boolean effective(LocalDate from, LocalDate until, LocalDate date) {
        return from != null && until != null && !date.isBefore(from)
                && (until.equals(BenefitPolicy.NO_END_DATE) || date.isBefore(until));
    }

    private boolean possiblyEffective(LocalDate from, LocalDate until, LocalDate date) {
        return (from == null || !date.isBefore(from))
                && (until == null || until.equals(BenefitPolicy.NO_END_DATE) || date.isBefore(until));
    }

    private Schedule schedule(String value) {
        if (value == null) {
            return Schedule.unrestricted(); // JSON 칸의 의미. 약관 조사 완료는 따로 보증하지 않는다.
        }
        try {
            JsonNode node = json.readTree(value);
            if (!node.isObject()) {
                throw new IllegalArgumentException("요일·시간 조건은 객체여야 합니다.");
            }
            node.fieldNames().forEachRemaining(key -> {
                if (!Set.of("weekdays", "startTime", "endTime").contains(key)) {
                    throw new IllegalArgumentException("미지원 조건 키");
                }
            });
            var days = EnumSet.noneOf(DayOfWeek.class);
            if (node.has("weekdays")) {
                var array = node.get("weekdays");
                if (!array.isArray() || array.isEmpty()) {
                    throw new IllegalArgumentException("요일 배열 형식 오류");
                }
                for (var day : array) {
                    DayOfWeek parsed = switch (day.asText()) {
                        case "MON" -> DayOfWeek.MONDAY;
                        case "TUE" -> DayOfWeek.TUESDAY;
                        case "WED" -> DayOfWeek.WEDNESDAY;
                        case "THU" -> DayOfWeek.THURSDAY;
                        case "FRI" -> DayOfWeek.FRIDAY;
                        case "SAT" -> DayOfWeek.SATURDAY;
                        case "SUN" -> DayOfWeek.SUNDAY;
                        default -> throw new IllegalArgumentException("알 수 없는 요일");
                    };
                    if (!day.isTextual() || !days.add(parsed)) {
                        throw new IllegalArgumentException("요일 중복·타입 오류");
                    }
                }
            }
            return new Schedule(days, time(node, "startTime"), time(node, "endTime"));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("조건 JSON 오류", e);
        }
    }

    private LocalTime time(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        var value = node.get(field);
        if (!value.isTextual() || !value.asText().matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw new IllegalArgumentException("HH:mm 형식 오류");
        }
        return LocalTime.parse(value.asText());
    }

    private void issue(List<RecommendationDataIssue> issues, UserCard card, Long policyId, String code) {
        issues.add(new RecommendationDataIssue(card.getId(), policyId, code, false));
    }
}
