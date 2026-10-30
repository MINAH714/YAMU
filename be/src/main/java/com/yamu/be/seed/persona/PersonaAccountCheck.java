package com.yamu.be.seed.persona;

import com.yamu.be.account.SsafyAccountApi;
import com.yamu.be.global.crypto.PersonalDataNormalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 페르소나의 userKey로 SSAFY 계좌 목록을 읽는다(계좌 연결과 같은 {@link SsafyAccountApi#accounts}).
 * 가입 중 계좌 연결은 이 목록에서 고르므로 0개면 그 페르소나로는 가입을 끝낼 수 없고,
 * 카드사가 정한 카드 계좌도 이 목록에 있어야 카드 등록 때 연결 계좌와 맞춰진다.
 */
@Component
@RequiredArgsConstructor
public class PersonaAccountCheck {

    private final SsafyAccountApi ssafyAccountApi;

    /**
     * 이 회원의 계좌를 "은행 코드:정규화 계좌번호"로 돌려준다(계좌 조회 해시의 입력과 같은 모양).
     * 금융망 실패(키 오류·없는 회원 포함)는 {@code FinApiException}을 그대로 던진다.
     */
    public Set<String> accountKeys(String userKey) {
        Set<String> keys = new LinkedHashSet<>();
        for (SsafyAccountApi.Account account : ssafyAccountApi.accounts(userKey)) {
            keys.add(PersonalDataNormalizer.accountLookupKey(account.bankCode(), account.accountNo()));
        }
        return keys;
    }
}
