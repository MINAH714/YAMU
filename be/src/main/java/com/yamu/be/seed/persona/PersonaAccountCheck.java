package com.yamu.be.seed.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.finapi.FinApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 페르소나의 userKey로 SSAFY 계좌 목록을 읽어 수시입출금 계좌가 몇 개인지 센다.
 * 가입 중 계좌 연결은 이 목록에서 고르므로, 0개면 그 페르소나로는 가입을 끝낼 수 없다.
 */
@Component
@RequiredArgsConstructor
public class PersonaAccountCheck {

    static final String LIST_PATH = "/edu/demandDeposit/inquireDemandDepositAccountList";

    private final FinApiClient finApiClient;

    /** 금융망 실패(키 오류·없는 회원 포함)는 {@code FinApiException}을 그대로 던진다. */
    public int countAccounts(String userKey) {
        JsonNode accounts = finApiClient.post(LIST_PATH, userKey, null, JsonNode.class);
        return accounts != null && accounts.isArray() ? accounts.size() : 0;
    }
}
