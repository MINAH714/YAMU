package com.yamu.be.recommendation;

import java.util.Map;
import java.util.Objects;

/** 서버 내부 거래 입력. availability는 기존 호출 호환용이며 실제 서비스 판정은 서버 조회 결과를 사용한다. */
public record RecommendationCommand(Long paymentId, long userId, RecommendationInput.Transaction transaction,
                                    Map<Long, PaymentAvailability> availability) {
    public RecommendationCommand(long paymentId, long userId, RecommendationInput.Transaction transaction,
            Map<Long, PaymentAvailability> availability) {
        this(Long.valueOf(paymentId), userId, transaction, availability);
    }

    public RecommendationCommand {
        if ((paymentId != null && paymentId <= 0) || userId <= 0) {
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
