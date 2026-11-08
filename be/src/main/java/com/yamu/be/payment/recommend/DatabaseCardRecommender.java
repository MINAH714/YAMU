package com.yamu.be.payment.recommend;

import com.yamu.be.recommendation.RecommendationCommand;
import com.yamu.be.recommendation.RecommendationInput;
import com.yamu.be.recommendation.RecommendationService;
import com.yamu.be.recommendation.RecommendationSelector;
import com.yamu.be.payment.recommend.RecommendationResult.*;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.LongFunction;
import org.springframework.stereotype.Component;

/** 검증·보존한 조건으로 서버 상태를 조회하는 실제 포트. 승인과 저장은 호출자가 수행한다. */
@Component
public class DatabaseCardRecommender implements CardRecommender {
    private final RecommendationService service;
    private final Clock clock;
    public DatabaseCardRecommender(RecommendationService service, Clock clock) { this.service=service; this.clock=clock; }

    public RecommendationResult recommend(RecommendationRequest request) {
        return convert(service.recommend(command(request)));
    }

    @Override
    public LongFunction<RecommendationResult> prepare(RecommendationRequest request) {
        var prepared = service.prepare(command(request));
        return paymentId -> convert(prepared.apply(paymentId));
    }

    private RecommendationCommand command(RecommendationRequest request) {
        var terms = request.terms();
        var preserved = new RecommendationInput.Transaction(terms.amount(), RecommendationInput.Plan.valueOf(terms.plan().name()),
                terms.installmentMonths() == null ? 1 : terms.installmentMonths(), request.requestedAt().toInstant(ZoneOffset.UTC),
                request.merchant().merchantId(), request.merchant().categoryCode(), request.verifiedFacts(), request.purchaseItemCode());
        var transaction=com.yamu.be.recommendation.BasicPurchaseSimulation.apply(preserved,request.merchant().channel(),request.merchant().facilityType());
        return new RecommendationCommand(request.paymentId(), request.userId().longValue(), transaction, request.availability());
    }

    private RecommendationResult convert(RecommendationService.Result result) {
        var candidates = new ArrayList<Candidate>();
        var excluded = new ArrayList<ExcludedCard>();
        var uncompared = new ArrayList<UncomparedCard>();
        for (var evaluated : result.recommendation().rankedCards()) {
            var context = result.cards().get(evaluated.userCardId());
            var calculation = evaluated.calculation();
            var versions = new HashMap<Long,Integer>();
            calculation.benefits().forEach(b -> versions.put(b.benefitId(), b.version()));
            var distinctVersions = versions.values().stream().distinct().toList();
            var lines = calculation.benefits().stream().map(b -> new BenefitLine(b.benefitId(), b.name(), b.type().name(),
                    b.rawValue(), b.value() == null ? null : b.value().longValueExact(), context.benefitTiming().get(b.benefitId()))).toList();
            var groups = calculation.unallocatedBenefitGroups().stream().map(g -> new UnallocatedBenefitGroup(g.benefitIds(),
                    g.limitGroupId(), g.totalAmount().longValueExact(), g.unit().name(), g.reasonCode())).toList();
            var uses = calculation.limitUses().stream().map(u -> new ExpectedLimitUse(u.limitId(), u.version(), u.unit().name(),
                    u.period().name(), u.remainingBefore(), u.consumed())).toList();
            var reason = Objects.equals(evaluated.userCardId(), result.recommendation().selectedUserCardId())
                    ? SelectionReason.valueOf(result.recommendation().selectionReason().name()) : null;
            candidates.add(new Candidate(candidates.size()+1, summary(evaluated.userCardId(),context), calculation.netBenefit(),
                    lines, result.recommendation().message(), reason,
                    new RuleVersion(context.cardProductId(), distinctVersions.size()==1 ? distinctVersions.get(0) : null, versions),groups,uses));
        }
        for (var evaluated : result.recommendation().evaluatedCards()) {
            var card = summary(evaluated.userCardId(), result.cards().get(evaluated.userCardId()));
            if (evaluated.status()==RecommendationSelector.CardStatus.EXCLUDED) {
                excluded.add(new ExcludedCard(card, ExclusionReason.valueOf(evaluated.reason()), evaluated.reason()));
            } else if (evaluated.status()==RecommendationSelector.CardStatus.NOT_COMPARED) {
                var issue = result.issues().stream().filter(i -> i.retryable()
                        && (i.userCardId()==null || i.userCardId()==evaluated.userCardId())).findFirst();
                var reason = evaluated.calculation() == null
                        ? UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN : UncomparedReason.BENEFIT_DATA_INSUFFICIENT;
                uncompared.add(new UncomparedCard(card, reason, issue.map(i -> i.message()).orElse(evaluated.message())));
            }
        }
        // BE3가 계산한 카드별 실적 인정 값을 그대로 옮긴다(Task 5.2.11). 결제 승인이 고른 카드의 값을 저장한다
        var recognitions = result.recognitions().values().stream()
                .map(r -> new PerformanceRecognition(r.userCardId(), r.period(), r.recognizedAmount())).toList();
        return new RecommendationResult("rec_"+UUID.randomUUID(), LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),false,candidates,excluded,uncompared,1,result.issues(),result.simulationRules(),recognitions);
    }

    private CardSummary summary(long id, RecommendationService.CardContext c) {
        return new CardSummary(id,c.cardProductId(),c.cardType(),c.defaultCard(),c.alias(),c.last4(),c.issuerCode(),c.productName());
    }
}
