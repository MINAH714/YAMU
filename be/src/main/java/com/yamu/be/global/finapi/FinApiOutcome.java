package com.yamu.be.global.finapi;

/**
 * 금융망 호출이 실패했을 때 금융망이 그 요청을 처리했는지. 출금·입금처럼 돈이 움직이는 호출은 이 값으로 다음 행동을 정한다.
 */
public enum FinApiOutcome {

    /** 요청이 금융망에 가지 않았다(연결 실패). 처리되지 않았으니 다시 보내도 된다. */
    NOT_SENT,

    /** 금융망이 오류 코드로 거절했다. 처리되지 않았다. 코드는 {@link FinApiException#getFinCode()}. */
    REJECTED,

    /**
     * 요청은 갔지만 결과를 모른다(응답 시간 초과, 응답이 깨짐). 금융망이 처리했을 수도 있다.
     * 출금·입금이면 다시 보내지 말고 거래내역을 조회해 결과를 확정한다(API 명세 1장 멱등성).
     */
    UNKNOWN
}
