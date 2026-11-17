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
import org.springframework.stereotype.Component;

/** 저장된 정책을 우선하며, 명시적으로 승인된 Seed 범위에만 추적 가능한 모의 계산 기준을 적용한다. */
@Component
class RecommendationInputAssembler {
    private final ObjectMapper json;
    private final List<CatalogScope> catalog;
    private static final LocalDate SIM_FROM = LocalDate.of(2026,11,1);
    private static final LocalDate SIM_UNTIL = LocalDate.of(2026,12,1);
    private record CatalogScope(String issuer, String product, String type, List<String> benefits, List<String> mockPointBenefits) { }

    RecommendationInputAssembler(ObjectMapper json) {
        this.json=json;
        try (var in = new org.springframework.core.io.ClassPathResource("recommendation/simulation-catalog.json").getInputStream()) {
            catalog=List.of(json.readValue(in,CatalogScope[].class));
        } catch (java.io.IOException failure) { throw new IllegalStateException("모의 정책 범위 자료를 읽지 못했습니다."); }
    }

    BenefitPolicy policy(UserCard card, RecommendationPolicyData data, LocalDate date, Long previous,
                         List<RecommendationDataIssue> issues) {
        return policy(card,data,date,previous,issues,null,null);
    }

    BenefitPolicy policy(UserCard card, RecommendationPolicyData data, LocalDate date, Long previous,
            List<RecommendationDataIssue> issues, RecommendationInput.Transaction transaction, com.yamu.be.card.domain.CardProduct product) {
        return policy(card,data,date,previous,issues,transaction,product,null);
    }

    BenefitPolicy policy(UserCard card, RecommendationPolicyData data, LocalDate date, Long previous,
            List<RecommendationDataIssue> issues, RecommendationInput.Transaction transaction,
            com.yamu.be.card.domain.CardProduct product, String merchantBrand) {
        var rows = data.benefits().stream().filter(b -> b.getCardProductId().equals(card.getCardProductId())).toList();
        if (rows.isEmpty()) {
            issue(issues, card, null, "POLICY_NOT_REGISTERED_FOR_TRANSACTION");
        }
        var scope=product==null || transaction==null || date.isBefore(SIM_FROM) || !date.isBefore(SIM_UNTIL) ? null
                : catalog.stream().filter(c -> c.issuer().equals(product.getIssuerCode()) && c.product().equals(product.getProductName())
                    && c.type().equals(product.getCardType().name())).findFirst().orElse(null);
        boolean scoped=scope!=null && rows.size()==scope.benefits().size() && rows.stream().allMatch(b -> b.getPolicyVersion()==1
                && scope.benefits().contains(b.getBenefitCode())) && rows.stream().map(CardBenefit::getBenefitCode).distinct().count()==rows.size();
        boolean ibk = product != null && "IBK".equals(product.getIssuerCode()) && "IBK나라사랑카드".equals(product.getProductName());
        if (ibk && transaction != null && "GS25".equals(merchantBrand) && transaction.basket() != null) {
            transaction.basket().items().stream().filter(item -> item.ibkEligible() == null)
                    .forEach(item -> issue(issues, card, null, "IBK_ITEM_CLASSIFICATION_UNKNOWN:" + item.productCode()));
        }
        if (ibk) scoped = transaction != null && !date.isBefore(SIM_FROM) && date.isBefore(SIM_UNTIL)
                && rows.size() == IbkPolicyRules.CODES.size()
                && rows.stream().allMatch(r -> r.getPolicyVersion() == 1 && IbkPolicyRules.CODES.contains(r.getBenefitCode()))
                && rows.stream().map(CardBenefit::getBenefitCode).distinct().count() == rows.size();
        boolean coverage = !ibk || transaction != null && IbkPolicyRules.coverage(card,date,previous,transaction,merchantBrand,issues);
        var traces=new ArrayList<SimulationRule>();
        if (!scoped) issue(issues,card,null,"POLICY_COMPLETENESS_UNVERIFIED");
        else if (!ibk) trace(traces,card,null,List.of("catalogScope=SEED_17_PRODUCTS_56_BENEFITS_VERSION_1"));
        else traces.add(new SimulationRule("YAMU_IBK_NOVEMBER",2,
                "2026년 11월 기존 보유자 정책입니다. 미지원·미확인 혜택은 완료 금액에 포함하지 않습니다.",card.getId(),null,
                List.of("newIssueWaiver=EXCLUDED","GS25_ONLY:rounding=DOWN_1_KRW","GS25_ONLY:fullCancellation=RESTORE_DAILY_COUNT")));
        var codes = new HashSet<String>();
        var converted = new ArrayList<Benefit>();
        var groupIds = new LinkedHashSet<Long>();
        var deferredConvenience = new HashSet<Long>();
        boolean otherRelevantBenefit = false;
        var effectiveRows = rows.stream().filter(r -> !ibk || !"NARA_CONVENIENCE".equals(r.getBenefitCode())
                || previous == null || previous < 250000).toList();
        // 601 사용자 승인 프로젝트 정책. 지정 POS 원금의 10%가 정수인 경우만 계산해 절사 규칙을 추정하지 않는다.
        boolean originalCashback = ibk && scoped && "GS25".equals(merchantBrand) && transaction.basket() != null
                && transaction.amount() % 10 == 0;
        for (var row : effectiveRows) {
            // 25만원 이상에서는 나라 편의점 서비스를 All in One으로 대체한다(한도 소진 시에도 되돌리지 않는다).
            if (ibk && "NARA_CONVENIENCE".equals(row.getBenefitCode()) && previous != null && previous >= 250000) continue;
            boolean instant = ibk && "GS25_INSTANT_10".equals(row.getBenefitCode());
            boolean naraCashback = originalCashback && "NARA_CONVENIENCE".equals(row.getBenefitCode())
                    && BigDecimal.valueOf(transaction.amount()).multiply(row.getRate()).remainder(BigDecimal.ONE).signum() == 0;
            if (!codes.add(row.getBenefitCode())) {
                issue(issues, card, row.getId(), "BENEFIT_VERSION_AMBIGUOUS");
            }
            var applied=new ArrayList<String>();
            var validity=validity(row.getValidFrom(),row.getValidUntil(),scoped,applied);
            if (!effective(validity[0], validity[1], date)) {
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
                    String key = target.getTargetValue();
                    // ZERO 원문: 국내외겸용 제한은 해외 이용에만 적용된다.
                    if (!target.isExcluded() && product != null && "HYUNDAI".equals(product.getIssuerCode())
                            && (("현대카드 ZERO Edition3 (할인형)".equals(product.getProductName())
                                && "BASE_DISCOUNT".equals(row.getBenefitCode()) && "RULE:OVERSEAS_ELIGIBILITY".equals(key))
                            || ("현대카드ZERO Edition3(포인트형)".equals(product.getProductName())
                                && "M_POINT_EARN_1_2".equals(row.getBenefitCode()) && "CARD_FORM:GLOBAL".equals(key)))) {
                        applied.add("condition=" + key + "->RULE:ZERO_OVERSEAS_ELIGIBILITY");
                        key = "RULE:ZERO_OVERSEAS_ELIGIBILITY";
                    }
                    (target.isExcluded() ? excluded : required).add(key);
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
            // 승인된 Seed의 모의 완전성이다. 실제 상품 약관 전체를 확인했다는 뜻이 아니다.
            var conditions = new Conditions(scoped, scoped, validity[0], validity[1], targets,
                    required, excluded, schedule, targetGroups(row, data));
            var mode=row.getRoundingMode()==null ? null : RoundingMode.valueOf(row.getRoundingMode());
            var unit=row.getRoundingUnit()==null ? null : BigDecimal.valueOf(row.getRoundingUnit());
            var stage=row.getRoundingStage();
            if (scoped && (!ibk || instant)) {
                applied.add(instant ? "baseAmount=verifiedBasket.ibkEligibleAmount" : "baseAmount=transaction.amount");
                applied.add("calculationOrder=LIMIT_THEN_ROUND");
                if (mode==null) { mode=RoundingMode.DOWN; applied.add("roundingMode=DOWN"); }
                if (unit==null) { unit=BigDecimal.ONE; applied.add("roundingUnit=1"); }
                if (stage==null) { stage=RoundingStage.PER_TRANSACTION; applied.add("roundingStage=PER_TRANSACTION"); }
            }
            if (naraCashback) {
                // 위의 정수 원 검증을 통과했으므로 기존 DOWN 연산이 금액을 바꾸지 않는다.
                mode=RoundingMode.DOWN;
                unit=BigDecimal.ONE;
                stage=RoundingStage.PER_TRANSACTION;
                applied.add("baseAmount=transaction.amount");
                applied.add("calculationOrder=LIMIT_THEN_ROUND");
                applied.add("roundingMode=DOWN(EXACT_KRW_ONLY)");
                applied.add("NARA_CONVENIENCE:projectOriginalAmount;exactKRW;fullCancellation=RESTORE_MONTHLY_COUNT");
                if (previous != null && previous >= row.getMinPerformanceAmount()) {
                    issues.add(new RecommendationDataIssue(card.getId(),row.getId(),RecommendationDataIssue.IBK_ORIGINAL_CASHBACK,false));
                }
            }
            var rounding=new Rounding(mode,unit,stage,null);
            var pointRate=row.getPointKrwRate();
            if (scoped && scope!=null && pointRate==null && scope.mockPointBenefits().contains(row.getBenefitCode())) {
                pointRate=BigDecimal.ONE; applied.add("pointKrwRate=1(MOCK_COMPARISON_ONLY)");
            }
            trace(traces,card,row.getId(),applied);
            if (row.getRoundingStage() == RoundingStage.AFTER_AGGREGATION) {
                issue(issues, card, row.getId(), "RAW_AGGREGATION_SCOPE_AND_VALUE_UNKNOWN");
            }
            Long baseAmount = !scoped ? null : instant
                    ? (transaction.basket() == null ? null : transaction.basket().ibkEligibleAmount()) : Long.valueOf(transaction.amount());
            boolean relevant = ibk && !instant && !naraCashback && (previous == null || previous >= row.getMinPerformanceAmount())
                    && new BenefitConditionEvaluator().evaluate(conditions,transaction) != BenefitConditionEvaluator.Match.NOT_MATCHED;
            if (relevant) {
                if (Set.of("NARA_CONVENIENCE", "AIO_CONVENIENCE").contains(row.getBenefitCode())) {
                    deferredConvenience.add(row.getId());
                } else {
                    otherRelevantBenefit = true;
                }
                IbkPolicyRules.issue(issues,card,"IBK_ROUNDING_AND_REVERSAL_UNKNOWN");
                if ("AIO_CONVENIENCE".equals(row.getBenefitCode())) IbkPolicyRules.issue(issues,card,"IBK_DAILY_COUNT_CONFLICT");
                if (("NARA_CONVENIENCE".equals(row.getBenefitCode()) || "AIO_CONVENIENCE".equals(row.getBenefitCode()))
                        && "GS25".equals(merchantBrand) && (transaction.basket() == null
                        || transaction.basket().ibkEligibleAmount() == null || transaction.basket().ibkEligibleAmount() > 0)) {
                    baseAmount = null;
                    IbkPolicyRules.issue(issues,card,"IBK_STACKED_BASE_UNKNOWN");
                }
            }
            converted.add(new Benefit(row.getId(), row.getPolicyVersion(), row.getBenefitName(), row.getBenefitType(),
                    row.getCalculationType(), row.getCalculationType() == CalculationType.RATE ? row.getRate() : row.getFixedValue(),
                    row.getValueUnit(), pointRate, baseAmount,
                    // 최소값은 승인된 Seed 범위에서 사용하며, 그 밖의 DEFAULT 0을 확인된 정책으로 승격하지 않는다.
                    row.getMinPaymentAmount(), row.getMinPerformanceAmount(), conditions, rounding, scoped && (!ibk || instant || naraCashback) ? CalculationOrder.LIMIT_THEN_ROUND : null, links));
        }
        var limits = new ArrayList<Limit>();
        for (Long groupId : groupIds) {
            var group = data.groups().stream().filter(g -> g.getId().equals(groupId)
                    && g.getCardProductId().equals(card.getCardProductId())).findFirst().orElse(null);
            if (group == null) {
                issue(issues, card, groupId, "LINKED_LIMIT_MISSING");
                continue;
            }
            var groupFields=new ArrayList<String>();
            boolean scopedGroup=scoped && group.getPolicyVersion()==1;
            var groupValidity=validity(group.getValidFrom(),group.getValidUntil(),scopedGroup,groupFields);
            trace(traces,card,null,groupFields.stream().map(f -> "limit:"+groupId+"."+f).toList());
            boolean valid = effective(groupValidity[0],groupValidity[1],date);
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
            boolean singleCount=scopedGroup && (!ibk || "GS25_INSTANT_DAILY".equals(group.getGroupCode())
                    || originalCashback && "NARA_CONVENIENCE_MONTH_COUNT".equals(group.getGroupCode())) && group.getLimitUnit()==Unit.COUNT && data.links().stream()
                    .filter(l -> l.getLimitGroupId().equals(groupId)).map(BenefitLimitLink::getCardBenefitId).distinct().count()==1;
            if (group.getLimitUnit()==Unit.COUNT && !singleCount) issue(issues,card,groupId,"COUNT_CONSUMPTION_POLICY_UNKNOWN");
            if (singleCount) trace(traces,card,null,List.of("limit:"+groupId+".countMode=PER_BENEFIT","limit:"+groupId+".consumeOnZero=false"));
            limits.add(new Limit(groupId, group.getPolicyVersion(), group.getLimitUnit(), group.getPeriodType(),
                    maximum, used, singleCount ? CountMode.PER_BENEFIT : null, singleCount ? false : null));
        }
        // POL-012·013: 현재 지원하는 일시불·YAMU 모의 무이자 할부의 금융 수수료·이자는 0원이다.
        // 비용 0원은 정책 완전성 판단을 바꾸지 않는다. 승인 범위 밖은 기존 미확인을 유지한다.
        var applications = applicationGroups(card, data, effectiveRows);
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
        if (applications.isEmpty() && !scoped) issue(issues,card,null,"BENEFIT_COMBINATIONS_UNKNOWN");
        var policy = new BenefitPolicy(scoped && coverage,previous,BigDecimal.ZERO,converted,limits,null,applications,fallbacks,traces);
        // 승인된 부분 계산은 GS25의 독립 현장할인에만 적용한다. 다른 미지원/자격 문제는 완전성 검사를 우회하지 않는다.
        if (ibk && policy.complete() && "GS25".equals(merchantBrand) && !otherRelevantBenefit
                && !deferredConvenience.isEmpty() && transaction.basket() != null
                && transaction.basket().ibkEligibleAmount() != null && transaction.basket().ibkEligibleAmount() > 0) {
            var instantRow = rows.stream().filter(r -> "GS25_INSTANT_10".equals(r.getBenefitCode())).findFirst().orElseThrow();
            var instant = converted.stream().filter(b -> b.id() == instantRow.getId()).findFirst().orElseThrow();
            var independent = IbkPolicyRules.confirmedInstant(policy, instant);
            if (independent != null) {
                issues.add(new RecommendationDataIssue(card.getId(), instant.id(), RecommendationDataIssue.IBK_CONFIRMED_ONLY, false));
                return independent;
            }
        }
        return policy;
    }

    private LocalDate[] validity(LocalDate from,LocalDate until,boolean scoped,List<String> fields) {
        if (scoped && (from==null || until==null)) {
            from=from==null || from.isBefore(SIM_FROM) ? SIM_FROM : from;
            until=until==null || until.isAfter(SIM_UNTIL) ? SIM_UNTIL : until;
            fields.add("validity=YAMU_MOCK_2026-11_KST");
        }
        return new LocalDate[]{from,until};
    }

    private void trace(List<SimulationRule> traces,UserCard card,Long benefit,List<String> fields) {
        if (!fields.isEmpty()) traces.add(new SimulationRule("YAMU_SIM_CARD_POLICY",1,
                "YAMU에 등록된 혜택과 모의 계산 기준으로 추천했습니다. 포인트의 비교 금액은 실제 환급액과 다를 수 있습니다.",card.getId(),benefit,fields));
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
            // Loader의 월별 조회가 정상 완료된 결과다. 그 달 행이 없으면 아직 혜택을 쓰지 않은 0이다(S15P31E103-324).
            // 행은 첫 혜택 사용 승인 때 결제 원장이 만든다. 조회 실패는 Loader에서 예외로 전달되므로 이 경로에 빈 결과로 들어오지 않는다.
            // 전제: 사용량 행은 한도 그룹 버전(limit_group_id)별이다. 새 버전은 다음 달부터 적용한다(schema/implementation.md 「정책」).
            // 월 중간에 새 버전을 적용하면 새 버전에는 행이 없어 그 달 앞서 쓴 사용량이 빠진 0이 된다. 버전 등록 기능을 만들 때 이 전제를 지킨다.
            var rows = data.monthly().stream().filter(u -> u.getUserCardId().equals(card.getId())
                    && u.getLimitGroupId().equals(group.getId())).toList();
            if (rows.isEmpty()) {
                return 0L;
            }
            // 같은 카드·그룹인데 상품이 다른 행은 자료 불일치다. 0으로 바꾸지 않고 미확인으로 남긴다.
            return rows.stream().filter(u -> u.getCardProductId().equals(card.getCardProductId()))
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
