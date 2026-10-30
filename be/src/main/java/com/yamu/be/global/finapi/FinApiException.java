package com.yamu.be.global.finapi;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import lombok.Getter;

/**
 * 금융망 호출 실패. 잡지 않으면 공통 에러 응답(502·504)으로 나간다.
 *
 * <p>개별 처리가 필요한 기능은 잡아서 {@link #getOutcome()}과 {@link #getFinCode()}를 본다.
 * 예: 체크카드 결제에서 {@code A1014}(잔액 부족)면 DECLINED, {@code UNKNOWN}이면 거래내역 조회.
 * 메시지에는 키·계좌번호 같은 값을 넣지 않는다(공통 핸들러가 메시지를 로그에 남김).
 * 금융망 코드는 앱 응답(details)에 넣지 않고 {@link FinApiClient}가 로그에만 남긴다. 앱에 보일 코드는 잡은 쪽이 명세 코드로 바꾼다.
 */
@Getter
public class FinApiException extends BusinessException {

    private final FinApiOutcome outcome;

    /** 금융망 응답 코드(예: A1014). 응답 코드가 없는 실패면 null. */
    private final String finCode;

    /** 호출한 API 이름. 기관거래고유번호 채번 실패처럼 API를 고르기 전에 실패하면 null. */
    private final String apiName;

    private FinApiException(ErrorCode errorCode, FinApiOutcome outcome, String finCode, String apiName) {
        super(errorCode);
        this.outcome = outcome;
        this.finCode = finCode;
        this.apiName = apiName;
    }

    static FinApiException notSent(String apiName) {
        return new FinApiException(ErrorCode.FIN_API_UNAVAILABLE, FinApiOutcome.NOT_SENT, null, apiName);
    }

    static FinApiException rejected(String apiName, String finCode) {
        return new FinApiException(ErrorCode.FIN_API_ERROR, FinApiOutcome.REJECTED, finCode, apiName);
    }

    /**
     * 결과 불명(504). 클라이언트가 응답 시간 초과로 던지고, 여러 금융망 호출을 묶어 전체 기한을 거는 쪽
     * (예: 계좌 잔액 동시 조회)도 기한을 넘긴 호출을 같은 의미로 나타낼 때 쓴다.
     */
    public static FinApiException timedOut(String apiName) {
        return new FinApiException(ErrorCode.UPSTREAM_TIMEOUT, FinApiOutcome.UNKNOWN, null, apiName);
    }

    static FinApiException brokenResponse(String apiName) {
        return brokenResponse(apiName, null);
    }

    /** 결과 불명. 서버 오류(5xx)처럼 코드는 왔지만 처리 여부를 믿을 수 없을 때 그 코드를 같이 담는다. */
    static FinApiException brokenResponse(String apiName, String finCode) {
        return new FinApiException(ErrorCode.FIN_API_UNAVAILABLE, FinApiOutcome.UNKNOWN, finCode, apiName);
    }
}
