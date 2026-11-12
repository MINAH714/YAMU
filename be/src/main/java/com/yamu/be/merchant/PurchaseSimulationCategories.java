package com.yamu.be.merchant;

import java.util.Set;

/**
 * 일반 모의 구매 범위. 기존 POS 사실 공급자가 미확인으로 두던 업종은 지원하지 않는다.
 * 유치원·학원·운전학원도 학교 납입금 여부가 불명확해 기존 보수적 범위에 포함된다(!180 리뷰).
 * 카드 약관의 제외 목록이 아니라 모의 구매의 입력 범위이며, 업종 사전/카드 정책은 유지한다.
 */
public final class PurchaseSimulationCategories {
    private PurchaseSimulationCategories() { }

    private static final Set<String> EXCLUDED = Set.of(
            "TAXI", "CITY_BUS", "INTERCITY_BUS", "EXPRESS_BUS", "SUBWAY", "RAILWAY",
            "ELECTRIC_UTILITY", "WATER_UTILITY", "CITY_GAS_UTILITY",
            "UNIVERSITY", "PRIMARY_SECONDARY_SCHOOL", "KINDERGARTEN", "ACADEMY", "DRIVING_SCHOOL");

    public static boolean supports(String category) {
        return category != null && !EXCLUDED.contains(category);
    }

    public static void requireSupported(String category) {
        MerchantCategories.requireSupported(category);
        if (!supports(category)) {
            throw MerchantCategories.invalid("merchantCategoryCode", "이 업종은 모의 구매를 지원하지 않아요. 다른 업종을 선택해 주세요.");
        }
    }
}
