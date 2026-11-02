package com.yamu.be.payment.approve;

import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;

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

    /**
     * 승인 전 사전 검사(카드 결정 뒤, PROCESSING 선점 전). 승인할 수 없음이 자료만으로 분명하면 여기서 예외를 던진다
     * (예: 신용카드 결제일 없음·한도 자료 없음 → 422 DATA_INSUFFICIENT). 선점·시도 횟수를 쓰지 않는다.
     */
    default void precheck(Long userId, CardSummary card, PaymentTerms terms) {}

    ApprovalOutcome approve(ApprovalCommand command);
}
