package com.yamu.be.payment.approve;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 결제 승인 설정({@code yamu.payment.approve.*}). 확정 마감 60초는 {@code yamu.pos.confirm-window}를 같이 쓴다.
 * 설정이 빠지면 첫 요청이 아니라 서버 기동 때 실패한다(횟수가 0이 되어 모든 승인이 409가 되는 것을 막는다).
 *
 * @param idempotencyTtl     Idempotency-Key 응답을 기억하는 시간(명세 1장: 24시간)
 * @param idempotencyLockTtl 같은 키를 처리 중이라고 표시해 두는 시간. 승인 실행(체크카드 출금의 금융망 호출 포함)보다 길게
 * @param startLockTtl       같은 결제의 시도 예약 ~ PROCESSING 선점을 한 줄로 세우는 잠금 시간. DB 트랜잭션 하나보다 길게
 * @param maxAttempts        거절 뒤 다른 카드로 다시 승인할 수 있는 횟수(임시: 명세 v0.3.1 가정 3회)
 * @param attemptTtl         결제별 승인 시도 기록을 Redis에 두는 시간. 결제 마감 이후 여유까지
 * @param settleLockTtl      결제 하나의 출금 실행·결과 저장·복구가 서로 겹치지 않게 잡는 잠금 시간. 금융망 응답 제한(10초)보다 넉넉히
 * @param recoveryGrace      결과를 모르는 출금을 "거래내역에 없음"으로 결론내기 전에 기다리는 시간(요청 번호 시각 기준). 금융망이 늦게 처리할 수 있어서
 * @param recoveryInterval   결과 불명 PROCESSING 결제를 훑는 주기 작업 간격
 * @param recoveryEnabled    주기 작업을 켤지. 테스트에서는 끈다
 */
@Validated
@ConfigurationProperties(prefix = "yamu.payment.approve")
public record PaymentApprovalProperties(
        @NotNull Duration idempotencyTtl,
        @NotNull Duration idempotencyLockTtl,
        @NotNull Duration startLockTtl,
        @Positive int maxAttempts,
        @NotNull Duration attemptTtl,
        @NotNull Duration settleLockTtl,
        @NotNull Duration recoveryGrace,
        @NotNull Duration recoveryInterval,
        boolean recoveryEnabled) {
}
