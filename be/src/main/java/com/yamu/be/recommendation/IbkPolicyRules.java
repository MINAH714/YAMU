package com.yamu.be.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.domain.UserCard;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/** IBK의 거래별 정책 범위. 미지원 혜택을 0원으로 치환하지 않는다. */
final class IbkPolicyRules {
    static final Set<String> CODES = Set.of("GS25_INSTANT_10", "NARA_CONVENIENCE", "AIO_CONVENIENCE",
            "AIO_CAFE", "AIO_MEDICAL", "AIO_SHOPPING", "AIO_LEISURE", "OUTBACK_INSTANT");
    private record Unavailable(String code, long minimumPerformance, List<String> brands,
                               List<String> categories, String requiredFact, boolean onlineOnly, boolean militaryOnly) { }
    private static final List<Unavailable> UNAVAILABLE;
    static {
        var json = new ObjectMapper();
        try (var in = new ClassPathResource("recommendation/ibk-unsupported.json").getInputStream()) {
            UNAVAILABLE = List.of(json.treeToValue(json.readTree(in).get("benefits"), Unavailable[].class));
        } catch (java.io.IOException e) { throw new IllegalStateException("IBK 미지원 정책 목록을 읽지 못했습니다.", e); }
    }
    private IbkPolicyRules() { }

    /** 다른 혜택과 한도/택일/전환을 공유하지 않는 현장할인만 별도의 계산 범위로 만든다. */
    static BenefitPolicy confirmedInstant(BenefitPolicy policy, BenefitPolicy.Benefit instant) {
        if (!policy.fallbacks().isEmpty() || instant.limitIds().size() != 1
                || policy.applicationGroups() == null) return null;
        var groups = policy.applicationGroups().stream()
                .filter(g -> g.members().stream().anyMatch(m -> m.benefitId() == instant.id())).toList();
        if (groups.size() != 1 || groups.get(0).mode() != BenefitPolicy.ApplicationMode.STACK) return null;
        long limitId = instant.limitIds().get(0);
        if (policy.benefits().stream().anyMatch(b -> b.id() != instant.id() && b.limitIds().contains(limitId))) return null;
        var limits = policy.limits().stream().filter(l -> l.id() == limitId).toList();
        if (limits.size() != 1 || limits.get(0).period() != BenefitPolicy.Period.DAILY
                || limits.get(0).unit() != BenefitPolicy.Unit.COUNT) return null;
        var traces = new java.util.ArrayList<>(policy.simulationRules());
        traces.add(new SimulationRule(RecommendationDataIssue.IBK_CONFIRMED_ONLY, 1,
                RecommendationDataIssue.IBK_CONFIRMED_NOTICE, null, instant.id(), List.of("confirmedIndependentBenefitOnly")));
        return new BenefitPolicy(true, policy.previousMonthPerformance(), policy.additionalCost(), List.of(instant), limits,
                null, List.of(new BenefitPolicy.ApplicationGroup(groups.get(0).id(), BenefitPolicy.ApplicationMode.STACK,
                    List.of(new BenefitPolicy.ApplicationMember(instant.id(), null)))), java.util.Map.of(), traces);
    }

    static boolean coverage(UserCard card, LocalDate date, Long previous, RecommendationInput.Transaction transaction,
            String brand, List<RecommendationDataIssue> issues) {
        boolean complete = true;
        if (!Boolean.TRUE.equals(transaction.facts().get("REGION:DOMESTIC"))) {
            issue(issues,card,"IBK_REGION_UNKNOWN_OR_UNSUPPORTED"); complete = false;
        }
        if ("VETERINARY_CLINIC".equals(transaction.merchantCategoryCode()) && (previous == null || previous >= 250000)) {
            issue(issues,card,"IBK_MEDICAL_CLASSIFICATION_UNKNOWN"); complete = false;
        }
        if (card.getIssuerRegisteredOn() == null) {
            issue(issues, card, "IBK_ISSUER_REGISTRATION_UNKNOWN"); complete = false;
        } else if (!date.isAfter(card.getIssuerRegisteredOn().withDayOfMonth(1).plusMonths(2).minusDays(1))) {
            issue(issues, card, "IBK_INTRODUCTORY_WAIVER_EXCLUDED"); complete = false;
        }
        boolean contextKnown = date.withDayOfMonth(1).equals(card.getIbkContextMonth())
                && card.getIbkSalaryQualified() != null && card.getIbkMilitaryQualified() != null;
        if (!contextKnown) {
            issue(issues, card, "IBK_MONTHLY_ELIGIBILITY_UNKNOWN"); complete = false;
        } else if (card.getIbkSalaryQualified() || card.getIbkMilitaryQualified()) {
            issue(issues, card, "IBK_MILITARY_PACKAGE_UNSUPPORTED"); complete = false;
        }
        if (previous == null) {
            issue(issues, card, "IBK_PREVIOUS_PERFORMANCE_UNKNOWN"); complete = false;
        }
        for (var benefit : UNAVAILABLE) {
            if (benefit.militaryOnly() && contextKnown && !card.getIbkSalaryQualified() && !card.getIbkMilitaryQualified()) continue;
            if (previous != null && previous < benefit.minimumPerformance()) continue;
            if (benefit.requiredFact() != null && Boolean.FALSE.equals(transaction.facts().get(benefit.requiredFact()))) continue;
            if (benefit.onlineOnly() && Boolean.FALSE.equals(transaction.facts().get("CHANNEL:ONLINE"))) continue;
            // 브랜드가 확인되면 업종만으로 다른 브랜드의 혜택을 적용하지 않는다.
            if (!benefit.brands().isEmpty() && brand != null && !benefit.brands().contains(brand)) continue;
            if ((brand == null || benefit.brands().isEmpty()) && !benefit.categories().isEmpty()
                    && transaction.merchantCategoryCode() != null && !benefit.categories().contains(transaction.merchantCategoryCode())) continue;
            issue(issues, card, "IBK_UNSUPPORTED_" + benefit.code()); complete = false;
        }
        return complete;
    }
    static void issue(List<RecommendationDataIssue> issues, UserCard card, String code) {
        issues.add(new RecommendationDataIssue(card.getId(), null, code, false));
    }
}
