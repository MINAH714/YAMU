package com.yamu.be.payment.billing.query;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.payment.billing.RefundStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 환급 내역(노션 API 명세 V2 「환급 내역 조회」, Task 5.4.8). 합계 아래에 공통 페이지 칸(items·page·size…)이 펼쳐진다.
 *
 * <p>체크카드 취소 환불은 환급 표({@code refund})에 행이 없다(S15P31E103-204 결정: 입금 성공으로 취소가 끝남). 그래서 취소된 체크카드
 * 결제를 {@code refundId = null}, 금액 = 승인액, 생성·완료 시각 = 취소 시각, 상태 COMPLETED로 함께 보인다(2026-11-06 결정).
 *
 * <p>무효(CANCELED) 환급은 원 청구서가 미납으로 끝나 돌려줄 돈이 없는 건이라 목록에는 남기되 금액을 0으로 보인다(!303 리뷰 반영).
 */
public record RefundListResponse(
        Summary summary,
        @JsonUnwrapped PageResponse<Item> list) {

    /**
     * 입금 전 환급(신용카드, PENDING). 기간·상태 필터와 상관없이 지금 남은 것만 센다.
     *
     * @param pendingAmount   PENDING 환급 금액 합계(예상값 포함)
     * @param pendingCount    PENDING 환급 건수
     * @param estimatedAmount 그중 기한이 아직 없어 금액이 예상값인 환급의 합계(확정 합계 = pendingAmount − estimatedAmount)
     * @param estimatedCount  그중 기한이 아직 없는 환급 건수
     */
    public record Summary(long pendingAmount, long pendingCount, long estimatedAmount, long estimatedCount) {}

    /**
     * 환급 한 건.
     *
     * @param refundId         환급 번호. 체크카드 취소 환불은 null(환급 행이 없음)
     * @param billItemId       취소 회차. 체크카드는 null
     * @param billId           회차의 청구서. 체크카드는 null
     * @param reason           {@code CANCEL_AFTER_CONFIRM} 신용카드 확정 후 취소 · {@code CHECK_CANCEL} 체크카드 취소 환불
     * @param amount           환급액(신용카드는 회차 몫 = 청구액 − 배분 청구할인). 무효(CANCELED)는 0
     * @param estimated        기한이 아직 없어 금액이 예상값이면 true(출금 전 청구서의 PENDING 환급). 앱은 dueDate가 아니라 이 칸으로 판단한다
     * @param scheduledAfter   아직 출금 전인 청구서의 회차면 그 출금 예정일. 출금이 끝나야 환급 기한이 정해진다
     * @param dueDate          환급 기한(출금 완료일 + 3영업일). 출금 전이면 null. 체크카드는 취소일(한국 날짜)
     * @param finTransactionNo 입금의 금융망 거래 번호. 체크카드는 저장하지 않아 null
     * @param toAccount        환급 계좌. 체크카드는 취소 때 환불을 넣은 계좌(S15P31E103-323). 칸이 생기기 전 취소 건은
     *                         마이그레이션 때의 카드 연결 계좌, 카드를 해제해 계좌가 없었으면 null
     */
    public record Item(Long refundId, Long paymentId, Long billItemId, Long billId, Long userCardId, String nickname,
            String merchantName, String reason, long amount, boolean estimated, RefundStatus status,
            LocalDateTime createdAt, LocalDate scheduledAfter, LocalDate dueDate, LocalDateTime completedAt,
            String finTransactionNo, Account toAccount) {}

    /** @param maskedAccountNo 끝 4자리만 보이는 계좌번호(청구서 조회와 같은 형식) */
    public record Account(Long accountId, String bankName, String maskedAccountNo) {}
}
