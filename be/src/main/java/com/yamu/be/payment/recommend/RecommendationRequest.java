package com.yamu.be.payment.recommend;

import com.yamu.be.payment.PaymentTerms;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 추천 입력(명세 「카드 추천 공통 처리」 2.2 최소 연결 규격). 사용자는 서버가 바코드에서 찾고,
 * 결제 조건은 서버가 검증해 보존한 POS 요청이다(앱 입력이 아님).
 *
 * @param paymentId   결제 요청 번호({@code payment_transaction}). 추천 결과를 이 결제에 연결한다
 * @param userId      결제하는 사용자
 * @param terms       POS 결제 조건(금액·결제 방식·개월 수)
 * @param merchant    인증 POS의 서버 설정에서 확인한 브랜드·업종·채널·시설
 * @param requestedAt 결제 요청 시각(UTC). 계산 기준 시각으로 명시해 넘긴다(요일·시간 조건과 실적 월 판정)
 */
public record RecommendationRequest(Long paymentId, Long userId, PaymentTerms terms, MerchantContext merchant,
        LocalDateTime requestedAt) {

    public RecommendationRequest {
        Objects.requireNonNull(paymentId, "paymentId");
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
