package com.yamu.be.payment.approve;

/**
 * 승인 실행 포트. 결제 행이 PROCESSING으로 저장된 뒤, DB 트랜잭션 밖에서 불린다.
 *
 * <p>구현이 맡는 일(명세 「결제 승인 요청」 처리 순서 2~4): 저장된 POS 조건과 추천 때 규칙 버전으로 혜택 재계산, 카드 상태·미납·한도·잔액 재확인,
 * 체크카드는 금융망 출금(Task 5.2.3), 신용카드는 가용 한도 확인(Task 5.2.4). 확정 거절은 {@link ApprovalOutcome.Declined}로 돌려주고,
 * 금융망 무응답 같은 결과 불명은 예외로 던진다(결제는 PROCESSING에 남는다). 금융망에 보내지도 못했으면
 * {@link ApprovalNotSentException}을 던져 결제를 되돌리게 한다.
 *
 * <p>기본 구현은 카드 종류로 나누는 {@link CardTypeRoutingApprover}다({@link CheckCardApprover}·{@link CreditCardApprover}).
 */
public interface PaymentApprover {

    ApprovalOutcome approve(ApprovalCommand command);
}
