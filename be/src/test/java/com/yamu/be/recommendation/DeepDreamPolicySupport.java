package com.yamu.be.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.yamu.be.recommendation.BenefitPolicy.*;

/** EXP-005 adapter only. Does not expand production's approved simulation catalog. */
final class DeepDreamPolicySupport {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    enum Kind { CREDIT, CHECK }
    enum Origin { SYNTHETIC_TEST_ONLY, REAL_CASE }
    enum Area { NONE, DISCOUNT_STORE, RETAIL_STORE, ENJOY_STORE, ABROAD, MOBILE }
    record Tier(long minimum, Long maximum, long cap) { }
    record Terms(Kind kind, String product, BigDecimal base, BigDecimal bonus, BigDecimal extra,
                 BigDecimal deferred, long minimum, List<Tier> tiers) {
        Terms { tiers = List.copyOf(tiers); }
        long cap(long previous) {
            if (previous < 0) throw new IllegalArgumentException("NEGATIVE_PREVIOUS_PERFORMANCE");
            var matches = tiers.stream().filter(t -> previous >= t.minimum() && (t.maximum() == null || previous < t.maximum())).toList();
            if (matches.size() != 1) throw new IllegalStateException("TIER_NOT_UNIQUE");
            return matches.get(0).cap();
        }
    }
    /** Every synthetic resolution is labelled; null research policy values are never defaulted. */
    record Rules(Origin origin, String evidence, LocalDate from, LocalDate until,
                 RoundingStage componentRounding, CalculationOrder order, Boolean bonusStacksWithDream,
                 String deferredRounding, String tieRule, Boolean immediateBeforeDeferred) {
        List<String> immediateIssues() {
            var result = new ArrayList<String>();
            if (origin == null || evidence == null || evidence.isBlank()) result.add("POLICY_EVIDENCE_MISSING");
            if (from == null || until == null || (from != null && until != null && !until.isAfter(from))) result.add("POLICY_PERIOD_UNCONFIRMED");
            if (componentRounding == null) result.add("COMPONENT_ROUNDING_UNCONFIRMED");
            else if (componentRounding != RoundingStage.PER_TRANSACTION) result.add("COMPONENT_ROUNDING_UNSUPPORTED");
            if (order == null) result.add("CALCULATION_ORDER_UNCONFIRMED");
            if (!Boolean.TRUE.equals(immediateBeforeDeferred)) result.add("SHARED_CAP_SETTLEMENT_ORDER_UNCONFIRMED");
            return List.copyOf(result);
        }
    }
    /** Sources must justify NONE as well as positive areas. No merchant-name inference. */
    record Facts(Boolean pointEligible, Area area, Boolean bonusEligible, Boolean specialBenefitsAbsent,
                 Long benefitBaseAmount, Long recognizedAmount, YearMonth recognitionMonth,
                 Long debitAmount, BigDecimal additionalCost, Long areaSelectionAmount, Long deferredEligibleAmount, String evidence) {
        List<String> issues(Kind kind, long amount) {
            var issues = new ArrayList<String>();
            if (evidence == null || evidence.isBlank()) issues.add("TRANSACTION_EVIDENCE_MISSING");
            if (pointEligible == null) issues.add("POINT_ELIGIBILITY_UNKNOWN");
            if (area == null) issues.add("DREAM_AREA_UNKNOWN");
            if (kind == Kind.CREDIT && bonusEligible == null) issues.add("ACCOUNT_OR_CONTACTLESS_UNKNOWN");
            if (!Boolean.TRUE.equals(specialBenefitsAbsent)) issues.add("SPECIAL_BENEFIT_APPLICABILITY_UNRESOLVED_OR_UNSUPPORTED");
            if (benefitBaseAmount == null || benefitBaseAmount < 0 || benefitBaseAmount > amount) issues.add("BENEFIT_BASE_UNCONFIRMED");
            if (recognizedAmount == null || recognizedAmount < 0 || recognizedAmount > amount || recognitionMonth == null) issues.add("PERFORMANCE_RECOGNITION_UNCONFIRMED");
            if (debitAmount == null || debitAmount < amount) issues.add("PAYMENT_AMOUNT_UNCONFIRMED");
            if (additionalCost == null || additionalCost.signum() < 0) issues.add("COST_UNCONFIRMED");
            if (areaSelectionAmount == null || areaSelectionAmount < 0 || areaSelectionAmount > amount
                    || deferredEligibleAmount == null || deferredEligibleAmount < 0 || deferredEligibleAmount > amount)
                issues.add("MONTHLY_AREA_BASE_UNCONFIRMED");
            if (area == Area.NONE && ((areaSelectionAmount != null && areaSelectionAmount != 0) || (deferredEligibleAmount != null && deferredEligibleAmount != 0)))
                issues.add("NON_DREAM_AREA_TOTAL_INVALID");
            if (Boolean.FALSE.equals(pointEligible) && deferredEligibleAmount != null && deferredEligibleAmount > 0)
                issues.add("EXCLUDED_PURCHASE_HAS_DEFERRED_BASE");
            return List.copyOf(issues);
        }
    }
    record Purchase(String id, Origin origin, Instant at, long amount, Kind actualCard, Map<Kind, Facts> facts) {
        Purchase {
            if (id == null || !id.matches("[A-Z0-9_-]+") || amount <= 0 || at == null || origin == null || actualCard == null)
                throw new IllegalArgumentException("INVALID_PURCHASE");
            facts = Map.copyOf(facts);
        }
    }
    static final class Unresolved extends IllegalStateException {
        final List<String> codes;
        Unresolved(List<String> codes) { super(String.join(",", codes)); this.codes = List.copyOf(codes); }
    }
    static JsonNode resource(String name) {
        try (var in = DeepDreamPolicySupport.class.getResourceAsStream("/recommendation/" + name)) {
            if (in == null) throw new IllegalStateException("RESOURCE_MISSING");
            return new ObjectMapper().readTree(in);
        } catch (IOException e) { throw new IllegalStateException("RESOURCE_UNREADABLE", e); }
    }
    static Map<Kind, Terms> terms() {
        var result = new EnumMap<Kind, Terms>(Kind.class);
        for (var card : resource("deep-dream-policy.json").get("cards")) {
            var tiers = new ArrayList<Tier>();
            for (var tier : card.get("tiers")) tiers.add(new Tier(tier.get(0).asLong(), tier.get(1).isNull() ? null : tier.get(1).asLong(), tier.get(2).asLong()));
            Kind kind = Kind.valueOf(card.get("kind").asText());
            result.put(kind, new Terms(kind, card.get("product").asText(), decimal(card,"baseRate"), decimal(card,"conditionalBonusRate"),
                    decimal(card,"dreamExtraRate"), decimal(card,"deferredExtraRate"), card.get("minimumPerformance").asLong(), tiers));
        }
        return Collections.unmodifiableMap(result);
    }
    private static BigDecimal decimal(JsonNode node, String key) { return new BigDecimal(node.get(key).asText()); }
    static Rules researchRules() {
        var p = resource("deep-dream-policy.json");
        // Read nullable policy fields rather than replacing them with existing mock defaults.
        return new Rules(Origin.REAL_CASE, "deep-dream-policy.json:v"+p.get("version").asInt(),
                p.get("validFrom").isNull() ? null : LocalDate.parse(p.get("validFrom").asText()),
                p.get("validUntil").isNull() ? null : LocalDate.parse(p.get("validUntil").asText()),
                p.get("componentRoundingStage").isNull() ? null : RoundingStage.valueOf(p.get("componentRoundingStage").asText()),
                p.get("calculationOrder").isNull() ? null : CalculationOrder.valueOf(p.get("calculationOrder").asText()),
                null, null, null, null);
    }

    /** Verify DB registration before using the adapter. Does not certify unknown rules. */
    static void verifyRegisteredPolicy(JdbcTemplate jdbc) {
        for (var terms : terms().values()) {
            Long product = jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE issuer_code='SHINHAN' AND product_name=? AND card_type=?", Long.class, terms.product(), terms.kind().name());
            var rates = Map.of("DD_BASE", terms.base(), "DD_DREAM_EXTRA", terms.extra(), "DD_DEFERRED_EXTRA", terms.deferred());
            for (var entry : rates.entrySet()) {
                var rows = jdbc.queryForList("SELECT rate,rounding_stage,valid_from,valid_until FROM card_benefit WHERE card_product_id=? AND benefit_code=? AND policy_version=1", product, entry.getKey());
                if (rows.size()!=1 || new BigDecimal(rows.get(0).get("rate").toString()).compareTo(entry.getValue())!=0)
                    throw new IllegalStateException("REGISTERED_POLICY_MISMATCH");
                if (rows.get(0).get("rounding_stage")!=null || rows.get(0).get("valid_from")!=null || rows.get(0).get("valid_until")!=null)
                    throw new IllegalStateException("UNKNOWN_POLICY_WAS_FILLED");
            }
            var tiers = jdbc.query("SELECT t.min_performance_amount,t.max_performance_amount,t.limit_value FROM tier_limit t JOIN limit_group g USING(limit_group_id) WHERE g.card_product_id=? AND g.group_code='DD_EXTRA_MONTHLY' AND g.policy_version=1 ORDER BY t.min_performance_amount",
                    (rs,n) -> new Tier(rs.getLong(1), rs.getObject(2)==null ? null : rs.getLong(2), rs.getLong(3)), product);
            if (!tiers.equals(terms.tiers())) throw new IllegalStateException("REGISTERED_TIERS_MISMATCH");
        }
    }

    static RecommendationInput.Transaction transaction(Purchase p) {
        return new RecommendationInput.Transaction(p.amount(), RecommendationInput.Plan.LUMP_SUM, 1, p.at(), null, "EVIDENCE_RESOLVED", Map.of());
    }
    static BenefitPolicy policy(Terms t, Rules rules, Purchase p, DeepDreamReplayState.CardSnapshot state) {
        var issues = new ArrayList<>(rules.immediateIssues());
        if (rules.origin()!=p.origin()) issues.add("EVIDENCE_ORIGIN_MISMATCH");
        Facts facts = p.facts().get(t.kind());
        if (facts==null) issues.add("CARD_FACTS_MISSING"); else issues.addAll(facts.issues(t.kind(), p.amount()));
        if (!issues.isEmpty()) throw new Unresolved(issues);
        var date = p.at().atZone(SEOUL).toLocalDate();
        if (date.isBefore(rules.from()) || !date.isBefore(rules.until())) throw new Unresolved(List.of("POLICY_OUTSIDE_CONFIRMED_PERIOD"));
        if (state.usedExtra()<0 || state.usedExtra()>t.cap(state.previous())) throw new IllegalArgumentException("INVALID_CAP_STATE");
        boolean dream = facts.area()!=Area.NONE && facts.pointEligible();
        boolean bonus = t.kind()==Kind.CREDIT && facts.bonusEligible() && facts.pointEligible();
        if (bonus && dream && rules.bonusStacksWithDream()==null) throw new Unresolved(List.of("BONUS_WITH_DREAM_UNCONFIRMED"));
        if (bonus && dream && Boolean.FALSE.equals(rules.bonusStacksWithDream())) throw new Unresolved(List.of("NONSTACKING_BONUS_SCOPE_UNSUPPORTED"));
        var benefits = new ArrayList<Benefit>();
        benefits.add(benefit(1,"BASE",t.base(),0,Boolean.TRUE.equals(facts.pointEligible()), facts.benefitBaseAmount(),List.of(),rules));
        if (t.kind()==Kind.CREDIT) benefits.add(benefit(2,"BASE_BONUS",t.bonus(),0,bonus,facts.benefitBaseAmount(),List.of(),rules));
        benefits.add(benefit(3,"DREAM_EXTRA",t.extra(),t.minimum(),dream,facts.benefitBaseAmount(),List.of(1L),rules));
        // DD_DEFERRED_EXTRA is intentionally absent from per-transaction recommendation input.
        var limits = List.of(new Limit(1,1,Unit.POINT,BenefitPolicy.Period.MONTHLY,t.cap(state.previous()),state.usedExtra(),null,null));
        return new BenefitPolicy(true,state.previous(),facts.additionalCost(),benefits,limits,
                List.of(benefits.stream().map(Benefit::id).toList()));
    }
    private static Benefit benefit(long id,String name,BigDecimal rate,long minimum,boolean applies,long base,List<Long> limits,Rules rules) {
        // A known false predicate is represented by a nonmatching category, never by unknown facts.
        var target = applies ? new Target(TargetType.ALL,null,null,false) : new Target(TargetType.CATEGORY,null,"KNOWN_NOT_APPLICABLE",false);
        var conditions = new Conditions(true,true,rules.from(),rules.until(),List.of(target),Set.of(),Set.of(),Schedule.unrestricted());
        // Transaction category is set in the runner to a known synthetic/verified sentinel, not an inferred merchant category.
        return new Benefit(id,1,name,BenefitType.POINT,CalculationType.RATE,rate,Unit.POINT,BigDecimal.ONE,base,0L,minimum,
                conditions,new Rounding(RoundingMode.DOWN,BigDecimal.ONE,rules.componentRounding(),null),rules.order(),limits);
    }
}
