package com.yamu.be.merchant;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Seed 코드 사전을 정규화한 평면 목록. 물품 분류/거래 조건/계층 추론을 포함하지 않는다. */
public final class MerchantCategories {
    private MerchantCategories() { }
    public record Category(String merchantCategoryCode, String merchantCategoryName) { }

    private static final List<Category> CATEGORIES = List.of(
            // 2026-11-05 Task 297 구현 검토에서 표시명 승인. 코드/브랜드 배정은 기존 Seed 유지.
            new Category("HOSPITAL", "병원"),
            new Category("DENTAL_CLINIC", "치과"),
            new Category("KOREAN_MEDICINE_CLINIC", "한의원"),
            new Category("VETERINARY_CLINIC", "동물병원"),
            new Category("HYPERMARKET", "대형마트"),
            new Category("SUPERMARKET", "슈퍼마켓"),
            new Category("HEALTH_BEAUTY_STORE", "헬스·뷰티 매장"),
            new Category("ACADEMY", "학원"),
            new Category("ADULT_ENTERTAINMENT", "유흥업소"),
            new Category("BAKERY", "베이커리·제과점"),
            new Category("BOOKSTORE", "서점"),
            new Category("BUFFET_RESTAURANT", "뷔페"),
            new Category("CAR_REPAIR", "자동차 정비"),
            new Category("CHINESE_RESTAURANT", "중식"),
            new Category("CITY_BUS", "시내버스"),
            new Category("CITY_GAS_UTILITY", "도시가스"),
            new Category("COFFEE_SHOP", "카페"),
            new Category("CONVENIENCE_STORE", "편의점"),
            new Category("DELIVERY_APP", "배달앱"),
            new Category("DRIVING_SCHOOL", "자동차학원"),
            new Category("ELECTRIC_UTILITY", "전기요금"),
            new Category("EXPRESS_BUS", "고속버스"),
            new Category("FAST_FOOD", "패스트푸드"),
            new Category("GAS_STATION", "주유소"),
            new Category("GENERAL_RESTAURANT", "일반음식점"),
            new Category("HAIR_SALON", "미용실"),
            new Category("INTERCITY_BUS", "시외버스"),
            new Category("JAPANESE_RESTAURANT", "일식"),
            new Category("KINDERGARTEN", "유치원"),
            new Category("KOREAN_RESTAURANT", "한식"),
            new Category("LAUNDRY", "세탁소"),
            new Category("MEMBERSHIP_SUBSCRIPTION", "유료 멤버십"),
            new Category("MOVIE_THEATER", "영화관"),
            new Category("ONLINE_MARKETPLACE", "온라인 쇼핑"),
            new Category("OTHER", "기타"),
            new Category("PARKING", "주차장"),
            new Category("PHARMACY", "약국"),
            new Category("PRIMARY_SECONDARY_SCHOOL", "초중고 학교"),
            new Category("PUB", "주점"),
            new Category("RAILWAY", "철도"),
            new Category("READING_ROOM", "독서실"),
            new Category("STREAMING_SERVICE", "스트리밍"),
            new Category("STUDY_MATERIAL", "학습지"),
            new Category("SUBWAY", "지하철"),
            new Category("TAXI", "택시"),
            new Category("TELECOMMUNICATION", "통신"),
            new Category("UNIVERSITY", "대학·대학원"),
            new Category("WATER_UTILITY", "수도요금"),
            new Category("WESTERN_RESTAURANT", "양식")
    ).stream().sorted(Comparator.comparing(Category::merchantCategoryName)
            .thenComparing(Category::merchantCategoryCode)).toList();

    public static List<Category> list() { return CATEGORIES; }
    public static void requireSupported(String code) {
        if (code == null || CATEGORIES.stream().noneMatch(c -> c.merchantCategoryCode().equals(code))) {
            throw invalid("merchantCategoryCode", "지원하는 가맹점 업종 코드를 입력해 주세요.");
        }
    }
    static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }
}
