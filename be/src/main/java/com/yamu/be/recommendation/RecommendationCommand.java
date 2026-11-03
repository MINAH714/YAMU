package com.yamu.be.recommendation;

import java.util.Map;
import java.util.Objects;

/** 결제 담당이 검증·보존한 거래와 결제 가능 판정. 상품 정책을 호출자가 만들 필요는 없다. */
public record RecommendationCommand(long paymentId, long userId, RecommendationInput.Transaction transaction,
                                    Map<Long, PaymentAvailability> availability) {
    public RecommendationCommand {
        if (paymentId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("결제·사용자 ID는 양수여야 합니다.");
        }
        Objects.requireNonNull(transaction);
        availability = Map.copyOf(availability);
        if (availability.keySet().stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("카드 ID는 양수여야 합니다.");
        }
    }

    public record PaymentAvailability(RecommendationInput.Availability status, String reason) {
        public PaymentAvailability {
            Objects.requireNonNull(status);
            if (status != RecommendationInput.Availability.AVAILABLE && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("결제 불가·미확인 사유가 필요합니다.");
            }
        }
    }
}
