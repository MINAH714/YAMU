package com.yamu.be.payment.approve;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 승인 설정({@code yamu.payment.approve.*}). 확정 마감 60초는 {@code yamu.pos.confirm-window}를 같이 쓴다.
 *
 * @param idempotencyTtl     Idempotency-Key 응답을 기억하는 시간(명세 1장: 24시간)
 * @param idempotencyLockTtl 같은 키를 처리 중이라고 표시해 두는 시간. 승인 실행(체크카드 출금의 금융망 호출 포함)보다 길게
 * @param maxAttempts        거절 뒤 다른 카드로 다시 승인할 수 있는 횟수(임시: 명세 v0.3.1 가정 3회)
 * @param attemptTtl         결제별 승인 시도 기록을 Redis에 두는 시간. 결제 마감 이후 여유까지
 */
@ConfigurationProperties(prefix = "yamu.payment.approve")
public record PaymentApprovalProperties(
        Duration idempotencyTtl,
        Duration idempotencyLockTtl,
        int maxAttempts,
        Duration attemptTtl) {
}
