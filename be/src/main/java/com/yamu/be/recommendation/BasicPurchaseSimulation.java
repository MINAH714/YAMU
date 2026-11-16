package com.yamu.be.recommendation;

import java.util.*;

/** 승인된 일반 구매 사실만 보완한다. 채널/제휴수단/공식 경로/실적/사용량은 추정하지 않는다. */
public final class BasicPurchaseSimulation {
    private BasicPurchaseSimulation() { }
    private static final Set<String> FACILITIES = Set.of("GENERAL", "DEPARTMENT_STORE", "DISCOUNT_STORE", "DUTY_FREE",
            "OUTLET", "SHOPPING_MALL", "AIRPORT", "HOTEL", "RESORT", "TRAIN_STATION");
    private static final Set<String> SPECIAL = Set.of("GIFT_CERTIFICATE_PURCHASE", "GIFT_CARD_PURCHASE", "GIFT_CARD_TOPUP",
            "GIFTICON_PURCHASE", "GIFT_SERVICE_CERTIFICATE", "PREPAID_CARD_PURCHASE", "PREPAID_CARD_TOPUP", "PREPAID_TOPUP",
            "ELECTRONIC_PREPAID_PURCHASE", "ELECTRONIC_PREPAID_TOPUP", "POINT_TOPUP", "POINT_CHARGE", "NATIONAL_TAX", "LOCAL_TAX",
            "CUSTOMS_TAX", "ANNUAL_FEE", "FEE", "INTEREST", "LONG_TERM_CARD_LOAN", "SHORT_TERM_CARD_LOAN",
            "AUTO_DEBIT_SERVICE_FEE", "OVERSEAS_SERVICE_FEE", "INTERNATIONAL_BRAND_FEE", "MEMBERSHIP_FEE", "ATM_WITHDRAWAL",
            "ADMINISTRATIVE_FINE", "CRIMINAL_FINE", "LOCAL_NON_TAX_REVENUE", "NATIONAL_LOCAL_TAX_PUBLIC_BILL", "PUBLIC_DOCUMENT_FEE",
            // 2026-11-12 승인: 일반 모의 구매는 아래 특수 거래에 해당하지 않는다.
            // 상품별 RULE, 카드 구분, 구매 경로는 이 목록에 포함하지 않는다.
            "PREPAID_PURCHASE", "OVERSEAS_FEE", "TAX_OR_PUBLIC_CHARGE", "PUBLIC_CHARGES",
            "ELECTRICITY_BILL", "CITY_GAS_BILL", "WATER_BILL", "WATER_SEWAGE_BILL", "TV_LICENSE_FEE",
            "APARTMENT_MAINTENANCE", "RENT_PAYMENT", "PUBLIC_RENT", "REAL_ESTATE_RENT",
            "SOCIAL_INSURANCE", "EMPLOYMENT_INSURANCE", "HEALTH_INSURANCE", "INDUSTRIAL_ACCIDENT_INSURANCE", "NATIONAL_PENSION",
            "EDUCATION_PAYMENT", "SCHOOL_PAYMENT", "PRIMARY_SECONDARY_SCHOOL_PAYMENT", "PRIVATE_KINDERGARTEN_EDUCATION_FEE",
            "UNIVERSITY_TUITION", "GRADUATE_TUITION", "GOV_SUBSIDY", "GOVERNMENT_SUBSIDY",
            "CITY_BUS", "METRO_BUS", "INTERCITY_BUS", "EXPRESS_BUS", "SUBWAY", "TAXI_PAYMENT",
            "HIGHWAY_TOLL", "POSTPAID_HIPASS", "TMONEY_AUTHORIZATION", "TRANSIT_RF_PAYMENT",
            "LATE_CHARGE", "SERVICE_OF_PROCESS_FEE", "STAMP_TAX", "PUBLIC_INSTITUTION_MERCHANT",
            "AUTO_CASHBACK_TRANSACTION", "SMART_CASHBACK_TRANSACTION", "AUTO_INSTALLMENT",
            "UNAPPROVED_VOUCHER", "CANCELED", "CANCELED_TRANSACTION", "NON_PURCHASE");

    public static RecommendationInput.Transaction apply(RecommendationInput.Transaction original, String channel, String facility) {
        if (channel != null && !Set.of("ONLINE", "OFFLINE", "UNKNOWN").contains(channel)) throw new IllegalArgumentException("결제 채널 형식 오류");
        if (facility != null && !FACILITIES.contains(facility)) throw new IllegalArgumentException("시설 형식 오류");
        // 입력 경계를 우회한 호출도 제외 업종을 일반 구매로 바꾸지 않는다. 명시된 사실은 그대로 보존한다.
        if (!com.yamu.be.merchant.PurchaseSimulationCategories.supports(original.merchantCategoryCode())) return original;
        var facts = new HashMap<>(original.facts());
        var applied = new ArrayList<String>();
        if (channel != null && !channel.equals("UNKNOWN")) {
            confirmed(facts,"CHANNEL:ONLINE",channel.equals("ONLINE")); confirmed(facts,"CHANNEL:OFFLINE",channel.equals("OFFLINE"));
        }
        if (!facts.containsKey("REGION:DOMESTIC") && !facts.containsKey("REGION:OVERSEAS")) {
            put(facts,applied,"REGION:DOMESTIC",true); put(facts,applied,"REGION:OVERSEAS",false);
        } else {
            if (Boolean.TRUE.equals(facts.get("REGION:DOMESTIC"))) confirmed(facts,"REGION:OVERSEAS",false);
            if (Boolean.TRUE.equals(facts.get("REGION:OVERSEAS"))) confirmed(facts,"REGION:DOMESTIC",false);
        }
        if (facility != null) {
            for (var value : FACILITIES) confirmed(facts,"FACILITY:"+value,value.equals(facility));
            if (facility.equals("GENERAL")) { confirmed(facts,"FACILITY:LARGE_FACILITY_TENANT",false); confirmed(facts,"FACILITY:BUILDING_TENANT",false); }
        } else if (facts.keySet().stream().noneMatch(k->k.startsWith("FACILITY:"))) {
            for (var value : FACILITIES) put(facts,applied,"FACILITY:"+value,value.equals("GENERAL"));
            put(facts,applied,"FACILITY:LARGE_FACILITY_TENANT",false); put(facts,applied,"FACILITY:BUILDING_TENANT",false);
        }
        if (facts.keySet().stream().noneMatch(k->k.startsWith("RULE:") && (k.contains("ROUTE")||k.contains("EASY_PAY")||k.contains("AGENCY")))
                // POS의 Npay 비사용 사실은 일반 구매 모의 기준을 바꾸지 않는다.
                && facts.entrySet().stream().noneMatch(e->e.getKey().startsWith("PAYMENT_METHOD:")
                    && !("PAYMENT_METHOD:NPAY".equals(e.getKey()) && Boolean.FALSE.equals(e.getValue())))) {
            put(facts,applied,"RULE:AGENCY_SITE",false); put(facts,applied,"RULE:NON_PARTNER_EASY_PAY",false);
            put(facts,applied,"TRANSACTION_KIND:PG_MERCHANT",false); put(facts,applied,"TRANSACTION_KIND:PG_ONLY_MERCHANT",false);
        }
        if (original.purchaseItemCode()==null
                && facts.entrySet().stream().noneMatch(e -> e.getKey().startsWith("TRANSACTION_KIND:") && Boolean.TRUE.equals(e.getValue()))
                && !Boolean.TRUE.equals(facts.get("BILLING:AUTO_DEBIT"))
                && !Boolean.TRUE.equals(facts.get("BILLING:RECURRING_MEMBERSHIP"))) {
            SPECIAL.stream().sorted().forEach(k->put(facts,applied,"TRANSACTION_KIND:"+k,false));
        }
        // 선택한 구독 항목은 정기결제 여부의 증거가 아니다.
        for (var key : List.of("BILLING:AUTO_DEBIT","BILLING:RECURRING_MEMBERSHIP")) put(facts,applied,key,false);
        for (var key : List.of("ISSUER_DISCOUNT:APPLIED","ISSUER_DISCOUNT:HYUNDAI_DISCOUNT","ISSUER_DISCOUNT:HYUNDAI_DISCOUNT_APPLIED",
                "ISSUER_DISCOUNT:LOTTE_BILLING_DISCOUNT_LARGER","ISSUER_DISCOUNT:SAMSUNG_EVENT_BENEFIT_APPLIED",
                "ISSUER_DISCOUNT:SAMSUNG_OTHER_BENEFIT","ISSUER_DISCOUNT:SAMSUNG_OTHER_EVENT")) put(facts,applied,key,false);
        var rules = new ArrayList<>(original.simulationRules());
        if (!applied.isEmpty()) rules.add(new SimulationRule("YAMU_BASIC_PURCHASE",2,"YAMU 기본 모의 거래 기준",null,null,applied));
        return new RecommendationInput.Transaction(original.amount(),original.plan(),original.installmentMonths(),original.occurredAt(),
                original.merchantId(),original.merchantCategoryCode(),facts,original.purchaseItemCode(),rules,original.basket());
    }
    private static void put(Map<String,Boolean> facts,List<String> fields,String key,boolean value) {
        if (!facts.containsKey(key)) { facts.put(key,value); fields.add(key); }
    }
    private static void confirmed(Map<String,Boolean> facts,String key,boolean value) {
        if (facts.containsKey(key) && facts.get(key)!=value) throw new IllegalArgumentException("확인된 거래 조건 충돌: "+key);
        facts.put(key,value);
    }
}
