package com.yamu.be.payment.memo;

import java.time.LocalDateTime;

/**
 * 결제 메모 응답(API 명세 「결제 메모」).
 *
 * @param memo          저장된 메모. 지웠으면 null
 * @param memoUpdatedAt 메모를 남기거나 지운 시각(UTC)
 */
public record PaymentMemoResponse(Long paymentId, String memo, LocalDateTime memoUpdatedAt) {
}
