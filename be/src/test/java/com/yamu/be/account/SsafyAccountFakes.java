package com.yamu.be.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yamu.be.account.SsafyAccountApi.Balance;
import com.yamu.be.account.SsafyAccountApi.CheckResult;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiFailures;
import java.util.Map;

/**
 * 계좌 목록·1원 인증·잔액 조회를 쓰는 다른 패키지의 테스트(기기 재등록 등)가 가짜 SSAFY 금융망을 세울 때 쓴다.
 * 응답 타입·경로가 이 패키지 안에만 보여서 여기 둔다.
 */
public final class SsafyAccountFakes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SsafyAccountFakes() {
    }

    /** 계좌 목록은 신한은행(088) accountNo 하나, 인증코드는 code만 맞고, 잔액은 620,000원. */
    public static void stub(FinApiClient finApiClient, String userKey, String accountNo, String code) {
        ArrayNode list = JSON.createArrayNode();
        list.add(JSON.createObjectNode().put("bankCode", "088").put("bankName", "신한은행")
                .put("accountNo", accountNo).put("accountName", "월급통장").put("accountBalance", "620000"));
        when(finApiClient.post(eq(SsafyAccountApi.LIST_PATH), eq(userKey), any(), eq(JsonNode.class)))
                .thenReturn(list);
        when(finApiClient.post(eq(SsafyAccountApi.CHECK_AUTH_PATH), eq(userKey), any(), eq(CheckResult.class)))
                .thenAnswer(inv -> {
                    Map<?, ?> body = inv.getArgument(2);
                    if (code.equals(body.get("authCode"))) {
                        return new CheckResult("SUCCESS");
                    }
                    throw FinApiFailures.rejected("checkAuthCode", "A1090");
                });
        when(finApiClient.post(eq(SsafyAccountApi.BALANCE_PATH), eq(userKey), any(), eq(Balance.class)))
                .thenReturn(new Balance(620000L));
    }
}
