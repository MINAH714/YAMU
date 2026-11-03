package com.yamu.be.payment.result;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentStatus;
import java.time.LocalDateTime;

/**
 * POS 결제 결과 조회 응답(API 명세 「POS 결제 결과 조회」). POS 영수증에 필요한 값만 담고 사용자 개인정보(이름·카드 별칭)는 넣지 않는다.
 *
 * @param approvalNo      승인번호(임시 규칙 Q3: {@code ApprovalNumbers}). 승인되지 않았으면 null
 * @param approvedAt      승인 시각(UTC). 승인되지 않았으면 null. 취소된 결제도 원래 승인 시각을 둔다
 * @param amount          POS가 요청한 결제 금액
 * @param chargedAmount   확정 승인액({@code approved_amount}). 즉시할인을 뺀 영수증 금액이다. 승인되지 않았으면 null
 * @param instantDiscount 결제 금액 − 확정 승인액. 승인되지 않았으면 null
 * @param card            결제에 쓴(또는 거절된) 카드. 카드가 정해지지 않았거나 승인 처리 중이면 null
 * @param failure         확정 거절 사유. 거절된 뒤 마감이 지나 EXPIRED가 된 결제도 마지막 거절 사유를 둔다. 없으면 null
 * @param retryable       임시(명세 Q1 제안, 명세 반영 전): DECLINED일 때만. 마감 전이고 재승인 횟수가 남았으면 true.
 *                        false면 POS는 마감을 기다리지 않고 거절 영수증을 띄운다. 다른 상태는 null
 */
public record PosPaymentResult(
        Long paymentId,
        String orderNo,
        PaymentStatus status,
        String approvalNo,
        LocalDateTime approvedAt,
        long amount,
        Long chargedAmount,
        Long instantDiscount,
        Card card,
        Failure failure,
        Boolean retryable) {

    /**
     * @param issuerName        임시: 카드사 코드(예: HANA) 그대로. 카드사 이름 표는 card 패키지(BE1)에 있어 열어 받은 뒤 이름으로 바꾼다
     * @param maskedNumber      끝 4자리만 보이는 카드번호. 앞자리는 저장하지 않는다
     * @param installmentMonths 할부일 때만. 일시불은 null
     */
    public record Card(String issuerName, CardType cardType, String maskedNumber, PaymentPlan plan,
            Integer installmentMonths) {}

    /** @param code {@code FailureCode} 값. message는 사유별 기본 문구만 쓴다(앱용 문구에는 잔액 같은 값이 들어갈 수 있다) */
    public record Failure(String code, String message) {}
}
