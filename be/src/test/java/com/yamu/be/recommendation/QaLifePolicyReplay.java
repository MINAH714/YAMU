package com.yamu.be.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.domain.*;
import com.yamu.be.seed.qa.QaLifeScenario;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.yamu.be.recommendation.BenefitPolicy.*;

/** Local fixture compiler. Replays today's catalog, never authorizes a payment or calls a bank. */
public final class QaLifePolicyReplay {
    public static com.fasterxml.jackson.databind.JsonNode generate(ApplicationContext context, long user) {
        var json = new ObjectMapper();
        var root = json.createObjectNode();
        root.put("signup", "2026-04-01");
        root.put("policyAsOf", QaLifeScenario.BASE.toString());
        var entries = root.putArray("payments");
        var db = context.getBean(JdbcTemplate.class);
        var cards = context.getBean(UserCardRepository.class).findByUserId(user).stream()
            .sorted(Comparator.comparing(UserCard::getId)).toList();
        var loader = context.getBean(RecommendationPolicyLoader.class);
        var assembler = context.getBean(RecommendationInputAssembler.class);
        var data = loader.load(user, cards, QaLifeScenario.BASE);
        var performance = new HashMap<String,Long>();
        var usage = new HashMap<String,Long>();
        for (var p : QaLifeScenario.payments(QaLifeScenario.BASE)) {
            var month = YearMonth.from(p.date());
            if (p.date().isBefore(LocalDate.of(2026,4,1))) {
                performance.merge(p.card()+":"+month,p.amount(),Math::addExact);
                continue;
            }
            var card = cards.get(p.card());
            var product = context.getBean(CardProductRepository.class).findById(card.getCardProductId()).orElseThrow();
            var merchant = db.queryForMap("SELECT merchant_id,merchant_category_code FROM merchant WHERE brand_code=?",p.brand());
            var tx = BasicPurchaseSimulation.apply(new RecommendationInput.Transaction(p.amount(),
                RecommendationInput.Plan.LUMP_SUM,1,p.date().atStartOfDay(ZoneId.of("Asia/Seoul")).plusMinutes(p.slot()).toInstant(),
                ((Number)merchant.get("merchant_id")).longValue(),(String)merchant.get("merchant_category_code"),Map.of()),
                "COUPANG".equals(p.brand()) ? "ONLINE" : "OFFLINE", "GENERAL");
            var issues = new ArrayList<RecommendationDataIssue>();
            boolean instantFixture=p.card()==3 && p.date().equals(LocalDate.of(2026,11,8));
            if(instantFixture) {
                var basket=com.yamu.be.payment.pos.PosBasket.validate("GS25",List.of(
                    new com.yamu.be.payment.pos.PosBasket.Line("gs-jeyuk-dosirak",java.math.BigDecimal.valueOf(4))),p.amount());
                tx=new RecommendationInput.Transaction(tx.amount(),tx.plan(),tx.installmentMonths(),tx.occurredAt(),
                    tx.merchantId(),tx.merchantCategoryCode(),tx.facts(),tx.purchaseItemCode(),tx.simulationRules(),basket);
            }
            var policy = assembler.policy(card,data,QaLifeScenario.BASE,
                performance.getOrDefault(p.card()+":"+month.minusMonths(1),0L),issues,tx,product,p.brand());
            var benefits = policy.benefits().stream().map(b -> {
                var c = b.conditions();
                // Explicit retrospective CURRENT-policy scenario, not a claim about historic terms.
                var conditions = new Conditions(c.verified(),c.currentVersionVerified(),LocalDate.of(2026,4,1),
                    QaLifeScenario.BASE.plusDays(1),c.targets(),c.requiredFacts(),c.excludedFacts(),c.schedule(),c.targetGroups());
                return new Benefit(b.id(),b.version(),b.name(),b.type(),b.calculationType(),b.rateOrFixedValue(),b.unit(),
                    b.pointKrwRate(),b.baseAmount(),b.minimumPayment(),b.minimumPerformance(),conditions,b.rounding(),b.calculationOrder(),b.limitIds());
            }).toList();
            var limits = policy.limits().stream().map(l -> new Limit(l.id(),l.version(),l.unit(),l.period(),l.maximum(),
                l.period()==BenefitPolicy.Period.PER_TRANSACTION ? 0L : usage.getOrDefault(p.card()+":"+l.id()+":"+(l.period()==BenefitPolicy.Period.DAILY?p.date():month),0L),
                l.countMode(),l.consumeOnZero())).toList();
            policy = new BenefitPolicy(policy.complete(),policy.previousMonthPerformance(),policy.additionalCost(),benefits,limits,
                policy.allowedCombinations(),policy.applicationGroups(),policy.fallbacks(),policy.simulationRules());
            if(instantFixture) {
                // Replay only the independently verified instant-discount service. Cashback remains unmodelled;
                // do not turn incomplete stacking rules into a fictitious cashback payment.
                long instantId=data.benefits().stream().filter(b->b.getCardProductId().equals(card.getCardProductId())
                    && "GS25_INSTANT_10".equals(b.getBenefitCode())).findFirst().orElseThrow().getId();
                var instant=benefits.stream().filter(b->b.id()==instantId).findFirst().orElseThrow();
                policy=new BenefitPolicy(true,policy.previousMonthPerformance(),java.math.BigDecimal.ZERO,List.of(instant),
                    limits.stream().filter(l->instant.limitIds().contains(l.id())).toList(),List.of(List.of(instantId)));
            }
            tx = CheoeumPurchaseSimulation.apply(tx,card.getId(),product,policy,p.brand());
            var calculation = new CardBenefitCalculator().calculate(tx,policy,CalculationOptions.DEFAULT);
            var row = entries.addObject();
            row.put("key",p.card()+":"+p.date()+":"+p.slot()); row.put("gross",p.amount());
            row.put("channel","COUPANG".equals(p.brand())?"ONLINE":"OFFLINE");
            if(instantFixture) {
                row.put("calculationScope","GS25_INSTANT_10_ONLY");
                row.set("basket",json.valueToTree(tx.basket()));
            }
            var lines = row.putArray("benefits"); var uses = row.putArray("limits");
            long approvalDiscount=0, billingDiscount=0;
            // Only complete allocated calculations become monetary ledger entries.
            if (calculation.complete()) {
                for(var b:calculation.benefits()) if(b.value()!=null && b.value().signum()>0) {
                    var source=data.benefits().stream().filter(v->v.getId()==b.benefitId()).findFirst().orElseThrow();
                    var line=lines.addObject(); line.put("code",source.getBenefitCode()); line.put("value",b.value());
                    line.put("unit",b.unit().name()); line.put("krw",b.krwValue());
                    if(b.type()==BenefitType.DISCOUNT) {
                        if(source.getApplicationTiming().equals("AT_APPROVAL")) approvalDiscount+=b.krwValue().longValueExact();
                        if(source.getApplicationTiming().equals("AT_BILLING")) billingDiscount+=b.krwValue().longValueExact();
                    }
                }
                for(var u:calculation.limitUses()) if(u.consumed().signum()>0) {
                    var source=data.groups().stream().filter(v->v.getId()==u.limitId()).findFirst().orElseThrow();
                    var use=uses.addObject(); use.put("code",source.getGroupCode());use.put("before",u.remainingBefore());use.put("used",u.consumed());
                    if(u.period()!=BenefitPolicy.Period.PER_TRANSACTION) usage.merge(p.card()+":"+u.limitId()+":"+(u.period()==BenefitPolicy.Period.DAILY?p.date():month),u.consumed().longValueExact(),Math::addExact);
                }
            }
            row.put("approvalDiscount",approvalDiscount);row.put("billingDiscount",billingDiscount);
            // Preserve the established issuer performance fixture for these unchanged gross purchases.
            Long recognized=p.amount();
            row.put("recognized",recognized); row.put("complete",calculation.complete());
            var reasons=row.putArray("unresolved");
            calculation.problems().forEach(problem->reasons.add(problem.code()));
            // Unknown recognition stays NULL; it is not promoted to a known gross performance.
            if(recognized!=null) performance.merge(p.card()+":"+month,recognized,Math::addExact);
        }
        return root;
    }
}
