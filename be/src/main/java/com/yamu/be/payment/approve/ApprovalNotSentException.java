package com.yamu.be.payment.approve;

/**
 * 승인 실행이 금융망에 아무것도 보내지 못했다(기관거래고유번호 채번 실패, 연결 실패). 돈이 움직이지 않았으므로
 * 결제를 PROCESSING에 두지 않고 승인 전으로 되돌려 다시 승인할 수 있게 한다({@link PaymentApprovalService}).
 * 결과를 모르는 실패(응답 시간 초과 등)는 이 예외가 아니라 금융망 예외로 던지고 PROCESSING에 남긴다.
 */
public class ApprovalNotSentException extends RuntimeException {

    public ApprovalNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
