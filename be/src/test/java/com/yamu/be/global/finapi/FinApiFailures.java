package com.yamu.be.global.finapi;

/** 다른 패키지 테스트가 금융망 실패를 흉내 낼 때 쓴다. FinApiException의 생성은 FinApiClient만 하도록 막혀 있다. */
public final class FinApiFailures {

    private FinApiFailures() {
    }

    public static FinApiException rejected(String apiName, String finCode) {
        return FinApiException.rejected(apiName, finCode);
    }

    public static FinApiException notSent(String apiName) {
        return FinApiException.notSent(apiName);
    }

    public static FinApiException timedOut(String apiName) {
        return FinApiException.timedOut(apiName);
    }
}
