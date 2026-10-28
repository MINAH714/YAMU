package com.yamu.be.global.finapi;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.Map;
import lombok.Getter;

/**
 * 금융망 호출 실패. 잡지 않으면 공통 에러 응답(502·504)으로 나간다.
 *
 * <p>개별 처리가 필요한 기능은 잡아서 {@link #getOutcome()}과 {@link #getFinCode()}를 본다.
 * 예: 체크카드 결제에서 {@code A1014}(잔액 부족)면 DECLINED, {@code UNKNOWN}이면 거래내역 조회.
 * 메시지에는 키·계좌번호 같은 값을 넣지 않는다(공통 핸들러가 메시지를 로그에 남김).
 */
@Getter
public class FinApiException extends BusinessException {

    private final FinApiOutcome outcome;

    /** 금융망 응답 코드(예: A1014). 응답 코드가 없는 실패면 null. */
    private final String finCode;

    private final String apiName;

    private FinApiException(ErrorCode errorCode, FinApiOutcome outcome, String finCode, String apiName,
            Map<String, Object> details) {
        super(errorCode, errorCode.getMessage(), details);
        this.outcome = outcome;
        this.finCode = finCode;
        this.apiName = apiName;
    }

    static FinApiException notSent(String apiName) {
        return new FinApiException(ErrorCode.FIN_API_UNAVAILABLE, FinApiOutcome.NOT_SENT, null, apiName, null);
    }

    static FinApiException rejected(String apiName, String finCode) {
        return new FinApiException(ErrorCode.FIN_API_ERROR, FinApiOutcome.REJECTED, finCode, apiName,
                Map.of("finCode", finCode));
    }

    static FinApiException timedOut(String apiName) {
        return new FinApiException(ErrorCode.UPSTREAM_TIMEOUT, FinApiOutcome.UNKNOWN, null, apiName, null);
    }

    static FinApiException brokenResponse(String apiName) {
        return new FinApiException(ErrorCode.FIN_API_UNAVAILABLE, FinApiOutcome.UNKNOWN, null, apiName, null);
    }
}
