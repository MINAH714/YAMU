package com.yamu.be.recommendation;

import java.util.*;

/** 2026-11-12 사용자 승인: 처음 카드의 일반 모의 구매는 매장 직접 결제·포인트 미사용이다. */
final class CheoeumPurchaseSimulation {
    private static final Map<String, Boolean> DEFAULTS = Map.of(
            "CHANNEL:OFFLINE", true,
            "RULE:CHEOEUM_CAFE_ROUTE", true,
            "RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", true,
            "TRANSACTION_KIND:DELIVERY_APP", false,
            "TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE", false);

    private CheoeumPurchaseSimulation() { }

    /** 정책은 수정하지 않고, 이 카드의 계산에만 사용하는 불변 거래 복사본을 만든다. */
    static RecommendationInput.Transaction apply(RecommendationInput.Transaction transaction, long cardId,
            com.yamu.be.card.domain.CardProduct product, BenefitPolicy policy) {
        return apply(transaction, cardId, product, policy, null);
    }

    static RecommendationInput.Transaction apply(RecommendationInput.Transaction transaction, long cardId,
            com.yamu.be.card.domain.CardProduct product, BenefitPolicy policy, String merchantBrand) {
        if (product == null || !"SHINHAN".equals(product.getIssuerCode())
                || !"신한카드 처음".equals(product.getProductName()) || policy == null || !policy.complete()
                || policy.simulationRules().stream().noneMatch(r -> r.id().equals("YAMU_SIM_CARD_POLICY"))) {
            return transaction;
        }
        var facts = transaction.facts();
        boolean coupang = "COUPANG".equals(merchantBrand)
                && Boolean.TRUE.equals(facts.get("CHANNEL:ONLINE"))
                && !Boolean.TRUE.equals(facts.get("CHANNEL:OFFLINE"));
        if (transaction.purchaseItemCode() != null
                || transaction.simulationRules().stream().noneMatch(r -> r.id().equals("YAMU_BASIC_PURCHASE"))
                || (!coupang && (Boolean.TRUE.equals(facts.get("CHANNEL:ONLINE"))
                    || Boolean.FALSE.equals(facts.get("CHANNEL:OFFLINE"))))
                || Boolean.TRUE.equals(facts.get("BILLING:AUTO_DEBIT"))
                || Boolean.TRUE.equals(facts.get("BILLING:RECURRING_MEMBERSHIP"))
                || Boolean.TRUE.equals(facts.get("RULE:AGENCY_SITE"))
                || Boolean.TRUE.equals(facts.get("RULE:NON_PARTNER_EASY_PAY"))
                || facts.entrySet().stream().anyMatch(e -> e.getKey().startsWith("TRANSACTION_KIND:") && e.getValue())) {
            return transaction;
        }
        var applied = new ArrayList<String>();
        var supplemented = new HashMap<>(facts);
        var defaults = coupang ? Map.of("RULE:OFFICIAL_PURCHASE_ROUTE", true,
                "RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE", true,
                "TRANSACTION_KIND:DELIVERY_APP", false,
                "TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE", false) : DEFAULTS;
        defaults.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            // 명시된 true/false는 보존하고 누락된 거래 사실만 보완한다.
            if (!facts.containsKey(entry.getKey())) {
                supplemented.put(entry.getKey(), entry.getValue());
                applied.add(entry.getKey() + "=" + entry.getValue());
            }
        });
        if (applied.isEmpty()) return transaction;
        var traces = new ArrayList<>(transaction.simulationRules());
        traces.add(new SimulationRule(coupang ? "YAMU_CHEOEUM_COUPANG_PURCHASE" : "YAMU_CHEOEUM_STORE_PURCHASE", 1,
                coupang ? "신한카드 처음 모의 정책: 쿠팡 공식 앱 직접 구매·포인트 미사용·배달앱 및 상품권 거래 아님"
                        : "신한카드 처음 모의 정책: 매장 직접 결제·포인트 미사용·배달앱 및 상품권 거래 아님",
                cardId, null, applied));
        return new RecommendationInput.Transaction(transaction.amount(), transaction.plan(), transaction.installmentMonths(),
                transaction.occurredAt(), transaction.merchantId(), transaction.merchantCategoryCode(), supplemented,
                transaction.purchaseItemCode(), traces);
    }
}
