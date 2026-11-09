package com.yamu.be.payment.recommend;

import com.yamu.be.payment.PaymentTerms;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 추천 입력(명세 「카드 추천 공통 처리」 2.2 최소 연결 규격). 사용자는 서버가 바코드에서 찾고,
 * 결제 조건은 서버가 검증해 보존한 POS 요청이다(앱 입력이 아님).
 *
 * @param paymentId   결제 요청 번호({@code payment_transaction}). POS 저장 전 준비에는 null을 전달한다
 * @param userId      결제하는 사용자
 * @param terms       POS 결제 조건(금액·결제 방식·개월 수)
 * @param merchant    인증 POS의 서버 설정에서 확인한 브랜드·업종·채널·시설
 * @param requestedAt 결제 요청 시각(UTC). 계산 기준 시각으로 명시해 넘긴다(요일·시간 조건과 실적 월 판정)
 */
public record RecommendationRequest(Long paymentId, Long userId, PaymentTerms terms, MerchantContext merchant,
        LocalDateTime requestedAt, String purchaseItemCode, java.util.Map<Long, com.yamu.be.recommendation.RecommendationCommand.PaymentAvailability> availability,
        java.util.Map<String, Boolean> verifiedFacts) {
    public RecommendationRequest(Long paymentId, Long userId, PaymentTerms terms, MerchantContext merchant, LocalDateTime requestedAt) {
        this(paymentId, userId, terms, merchant, requestedAt, null, java.util.Map.of(), java.util.Map.of());
    }

    /**
     * 모의 POS 일반 구매의 추천 요청. POS가 확인한 거래 사실({@link PosPurchaseFacts})을 함께 넘겨 실적 인정 계산이 미확인으로 남지 않게 한다
     * (Task 5.2.11). POS 요청 때의 추천과 승인 전 재계산이 같은 입력을 쓴다.
     */
    public static RecommendationRequest posPurchase(Long paymentId, Long userId, PaymentTerms terms, MerchantContext merchant,
            LocalDateTime requestedAt) {
        return new RecommendationRequest(paymentId, userId, terms, merchant, requestedAt, null, java.util.Map.of(),
                PosPurchaseFacts.verified());
    }

    public RecommendationRequest {
        availability = java.util.Map.copyOf(availability);
        verifiedFacts = java.util.Map.copyOf(verifiedFacts);
        if (purchaseItemCode != null && !purchaseItemCode.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("구매 항목 코드 형식 오류");
        if (paymentId != null && paymentId <= 0) throw new IllegalArgumentException("결제 ID는 양수여야 합니다.");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /**
     * 가맹점 조건. POS 설정을 어디에 저장할지(Task 5.1.3) 정해지기 전이라 코드 값은 문자열로 둔다(임시).
     *
     * @param merchantId   브랜드 단위 MERCHANT ID
     * @param categoryCode YAMU 업종 코드(예: F01-CAFE). 모르면 null
     * @param channel      ONLINE / OFFLINE. 모르면 null
     * @param facilityType GENERAL / DEPARTMENT_STORE 등. 모르면 null
     */
    public record MerchantContext(Long merchantId, String categoryCode, String channel, String facilityType) {

        public MerchantContext {
            Objects.requireNonNull(merchantId, "merchantId");
        }
    }
}
