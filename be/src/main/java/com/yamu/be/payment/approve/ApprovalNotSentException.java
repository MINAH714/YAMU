package com.yamu.be.payment.approve;

import com.yamu.be.global.exception.BusinessException;
import java.util.Optional;

/**
 * 승인 실행이 금융망에 아무것도 보내지 못했다(기관거래고유번호 채번 실패, 연결 실패). 돈이 움직이지 않았으므로
 * 결제를 PROCESSING에 두지 않고 승인 전으로 되돌려 다시 승인할 수 있게 한다({@link PaymentApprovalService}).
 * 결과를 모르는 실패(응답 시간 초과 등)는 이 예외가 아니라 금융망 예외로 던지고 PROCESSING에 남긴다.
 *
 * <p>신용카드 승인은 외부로 보내는 것이 없으므로 실행 중 실패가 모두 이 예외다({@link CardTypeRoutingApprover}).
 * 그때는 되돌린 뒤 돌려줄 오류({@link #reply()})를 함께 싣는다.
 */
public class ApprovalNotSentException extends RuntimeException {

    private final BusinessException reply;

    public ApprovalNotSentException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /** @param reply 되돌린 뒤 돌려줄 오류(예: 실행 중 카드 해지 → 404). null이면 502 FIN_API_UNAVAILABLE */
    public ApprovalNotSentException(String message, Throwable cause, BusinessException reply) {
        super(message, cause);
        this.reply = reply;
    }

    /** 되돌린 뒤 돌려줄 오류. 비어 있으면 502 FIN_API_UNAVAILABLE이다. */
    public Optional<BusinessException> reply() {
        return Optional.ofNullable(reply);
    }
}
