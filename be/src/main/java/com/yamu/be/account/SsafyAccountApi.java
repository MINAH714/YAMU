package com.yamu.be.account;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 계좌 연결에 쓰는 SSAFY 금융망 API(노션 명세 「[SSAFY] 계좌 목록·잔액·예금주 조회」「[SSAFY] 1원 인증」).
 *
 * <p>SSAFY 응답 코드 중 사용자가 고칠 수 있는 것(계좌번호 오류, 인증코드 불일치·만료)만 YAMU 에러 코드로 바꾸고,
 * 나머지는 금융망 실패(502·504) 그대로 던진다.
 */
@Component
@RequiredArgsConstructor
public class SsafyAccountApi {

    static final String HOLDER_PATH = "/edu/demandDeposit/inquireDemandDepositAccountHolderName";
    static final String BALANCE_PATH = "/edu/demandDeposit/inquireDemandDepositAccountBalance";
    static final String OPEN_AUTH_PATH = "/edu/accountAuth/openAccountAuth";
    static final String CHECK_AUTH_PATH = "/edu/accountAuth/checkAuthCode";

    /** 계좌번호 오류 */
    private static final String ACCOUNT_INVALID = "A1003";
    /** 원화 수시입출금 계좌가 아님 */
    private static final String ACCOUNT_NOT_DEMAND_DEPOSIT = "A5004";
    /** 인증코드 발급 기록 없음(A1086), 인증 시간 만료(A1087) */
    private static final Set<String> CODE_EXPIRED = Set.of("A1086", "A1087");
    /** 인증코드 불일치. 문서는 A1088, 10/27 실측은 틀린 숫자·형식 오류 모두 A1090 */
    private static final Set<String> CODE_MISMATCH = Set.of("A1088", "A1090");

    private final FinApiClient finApiClient;

    /** 예금주. SSAFY의 userName은 실제 이름이 아니라 SSAFY 계정 ID의 {@code @} 앞부분이다. */
    public Holder holder(String userKey, String accountNo) {
        try {
            return finApiClient.post(HOLDER_PATH, userKey, Map.of("accountNo", accountNo), Holder.class);
        } catch (FinApiException e) {
            throw translateAccount(e);
        }
    }

    /** 1원을 보낸다. 통장 입금자명에 "{authText} {숫자 4자리}"가 찍힌다. */
    public void sendOneWon(String userKey, String accountNo, String authText) {
        try {
            finApiClient.post(OPEN_AUTH_PATH, userKey, Map.of("accountNo", accountNo, "authText", authText), Void.class);
        } catch (FinApiException e) {
            throw translateAccount(e);
        }
    }

    /**
     * 인증코드를 확인한다. 틀리면 400 AUTH_CODE_MISMATCH(remainingAttempts), 만료·송금 기록 없음은 400 AUTH_CODE_EXPIRED.
     *
     * @param remainingAttempts 이번 시도를 센 뒤 남은 횟수({@link OneWonAttempts#consumeTry})
     */
    public void checkCode(String userKey, String accountNo, String authText, String code, int remainingAttempts) {
        CheckResult result;
        try {
            result = finApiClient.post(CHECK_AUTH_PATH, userKey,
                    Map.of("accountNo", accountNo, "authText", authText, "authCode", code), CheckResult.class);
        } catch (FinApiException e) {
            if (isRejected(e, CODE_MISMATCH)) {
                throw mismatch(remainingAttempts);
            }
            if (isRejected(e, CODE_EXPIRED)) {
                throw new BusinessException(ErrorCode.AUTH_CODE_EXPIRED);
            }
            throw translateAccount(e);
        }
        // 성공 코드인데 SUCCESS가 아니면 맞다고 보지 않는다. 계좌가 걸린 일이라 애매하면 연결하지 않는 쪽.
        if (!"SUCCESS".equals(result.status())) {
            throw mismatch(remainingAttempts);
        }
    }

    /** 현재 잔액(원). SSAFY는 문자열("1250000")로 준다. */
    public long balance(String userKey, String accountNo) {
        return finApiClient.post(BALANCE_PATH, userKey, Map.of("accountNo", accountNo), Balance.class).accountBalance();
    }

    private static BusinessException mismatch(int remainingAttempts) {
        return new BusinessException(ErrorCode.AUTH_CODE_MISMATCH, Map.of("remainingAttempts", remainingAttempts));
    }

    private static BusinessException translateAccount(FinApiException e) {
        if (isRejected(e, Set.of(ACCOUNT_INVALID))) {
            return new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND_AT_BANK);
        }
        if (isRejected(e, Set.of(ACCOUNT_NOT_DEMAND_DEPOSIT))) {
            return new BusinessException(ErrorCode.ACCOUNT_NOT_SUPPORTED);
        }
        return e;
    }

    /** 금융망이 처리하지 않았다고 확실히 답한 경우만 본다. 결과 불명(5xx·시간 초과)은 코드가 있어도 그대로 던진다. */
    private static boolean isRejected(FinApiException e, Set<String> codes) {
        return e.getOutcome() == FinApiOutcome.REJECTED && codes.contains(e.getFinCode());
    }

    public record Holder(@NotBlank String bankCode, String bankName, @NotBlank String userName) {
    }

    record CheckResult(String status) {
    }

    record Balance(@NotNull Long accountBalance) {
    }
}
