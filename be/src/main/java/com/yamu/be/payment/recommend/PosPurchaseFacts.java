package com.yamu.be.payment.recommend;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 모의 POS 결제가 확인해 추천에 넘기는 거래 사실(Task 5.2.11). BE3 실적 인정 계산({@code RecommendationRecognitionResolver})은
 * 사실 키가 없으면 false로 치지 않고 미확인으로 둔다({@code docs/be/recommendation.md}: 사실 공급자가 확인한다).
 *
 * <p>여기 키는 POS 일반 구매에서 "아니다"가 확정인 것만 둔다.
 * <ul>
 *   <li>거래 상태: 새 승인 요청이므로 취소 거래·승인 없는 상품권 사용이 아니다</li>
 *   <li>카드사가 만드는 거래: 연체료·해외 수수료·자동 캐시백·정부 보조금 등은 POS 결제가 아니다</li>
 *   <li>교통·통신 자동납부: POS 단말 결제는 후불교통·통신요금 자동납부가 아니다</li>
 *   <li>공과금·세금·보험료·등록금·관리비·월세·통행료 등: 모의 가맹점(be/seed) 업종에 이런 가맹점이 없다(임시, 아래 한계)</li>
 * </ul>
 * 상품권·세금·대출 등 {@code BasicPurchaseSimulation}이 이미 채우는 키는 넣지 않는다(같은 값을 두 곳에서 정하지 않는다).
 *
 * <p>한계(임시): 공과금·등록금 같은 업종의 가맹점이 생기면 업종별로 판정해야 하고, 그 판정은 약관 해석이라 BE3와 정한다.
 * 신규 발급 유예({@code PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:*})는 결제가 알 수 없어 넘기지 않는다(최소 실적만 미확인이 되고
 * 인정월·인정액 계산에는 쓰이지 않는다).
 */
public final class PosPurchaseFacts {

    /** 실적 인정 규칙이 보는 거래 종류 중 POS 일반 구매가 아닌 것. */
    static final Set<String> NOT_POS_PURCHASE_KINDS = Set.of(
            // 거래 상태
            "CANCELED_TRANSACTION", "UNAPPROVED_VOUCHER",
            // 카드사가 만드는 거래
            "LATE_CHARGE", "OVERSEAS_FEE", "FX_HANAMONEY_PAYMENT", "AUTO_SMART_CASHBACK", "GOV_SUBSIDY",
            // 교통
            "POSTPAID_TRANSIT", "TAXI_PAYMENT", "EXPRESS_BUS", "HIGHWAY_TOLL",
            // 공과금·세금·보험료
            "TAX_OR_PUBLIC_CHARGE", "PUBLIC_CHARGES", "FINES_AND_PUBLIC_FEES", "ELECTRICITY_BILL", "WATER_BILL",
            "CITY_GAS_BILL", "TV_LICENSE_FEE", "ENVIRONMENTAL_IMPROVEMENT_CHARGE", "DISABLED_EMPLOYMENT_LEVY",
            "SOCIAL_INSURANCE",
            // 교육
            "UNIVERSITY_TUITION", "GRADUATE_TUITION", "SCHOOL_PAYMENT", "PRIMARY_SECONDARY_SCHOOL_PAYMENT",
            // 주거
            "APARTMENT_MAINTENANCE", "RENT_PAYMENT",
            // 주유(모의 주유소 가맹점은 일반 주유소다)
            "LPG_STATION");

    /** 인정월 규칙({@code monthRule})이 보는 사실. */
    static final Set<String> NOT_POS_PERFORMANCE_FACTS = Set.of(
            "PERFORMANCE:POSTPAID_TRANSIT", "PERFORMANCE:MOBILE_POSTPAID_TRANSIT", "PERFORMANCE:TELECOM_AUTOPAY");

    /**
     * 이 업종의 가맹점이면 위 사실(택시·후불교통·공과금·등록금 등이 "아니다")을 확정할 수 없다. 지금 모의 가맹점(be/seed)·POS 단말에는
     * 이 업종이 없다. 유치원·학원·운전학원은 학교 납입금({@code SCHOOL_PAYMENT})에 들어가는지 원문이 없어 넣었다: 넣고 틀리면 자료 부족,
     * 빼고 틀리면 혜택이 커진다. 목록이 빠짐없는지는 테스트로 증명할 수 없다(업종별 약관 해석, 2026-11-08 !180 리뷰).
     * 업종 단위라 막지 못하는 것: 주유소 업종 가맹점이 LPG 충전소면 {@code LPG_STATION=false}가 틀린다(POS도 같다).
     */
    static final Set<String> UNCONFIRMED_CATEGORIES = Set.of(
            "TAXI", "CITY_BUS", "INTERCITY_BUS", "EXPRESS_BUS", "SUBWAY", "RAILWAY",
            "ELECTRIC_UTILITY", "WATER_UTILITY", "CITY_GAS_UTILITY",
            "UNIVERSITY", "PRIMARY_SECONDARY_SCHOOL", "KINDERGARTEN", "ACADEMY", "DRIVING_SCHOOL");

    private static final Map<String, Boolean> VERIFIED = build();

    private PosPurchaseFacts() {
    }

    /** POS 일반 구매에서 확인된 사실. 모두 false다. */
    public static Map<String, Boolean> verified() {
        return VERIFIED;
    }

    /**
     * 그 업종 가맹점의 일반 구매에서 확인된 사실. 업종을 모르거나 {@link #UNCONFIRMED_CATEGORIES}이면 빈 Map이다.
     * 가상 구매(브랜드 선택)가 쓴다. POS 접수는 아직 {@link #verified()}를 쓴다(단말에 해당 업종이 없음, BE2 후속에서 옮김).
     *
     * @param merchantCategoryCode 가맹점의 업종 코드(DB 값). null이면 모름
     * @return 확인된 사실(모두 false) 또는 빈 Map
     */
    public static Map<String, Boolean> forCategory(String merchantCategoryCode) {
        if (merchantCategoryCode == null || UNCONFIRMED_CATEGORIES.contains(merchantCategoryCode)) {
            return Map.of();
        }
        return VERIFIED;
    }

    private static Map<String, Boolean> build() {
        var kinds = NOT_POS_PURCHASE_KINDS.stream().map(kind -> "TRANSACTION_KIND:" + kind);
        return java.util.stream.Stream.concat(kinds, NOT_POS_PERFORMANCE_FACTS.stream())
                .collect(Collectors.toUnmodifiableMap(key -> key, key -> Boolean.FALSE));
    }
}
