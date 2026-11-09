package com.yamu.be.account;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 계좌 연결에 쓰는 SSAFY 금융망 API(노션 명세 「[SSAFY] 계좌 목록·잔액·예금주 조회」「[SSAFY] 1원 인증」).
 * 예금주 조회는 쓰지 않는다. 연결 후보를 이 사용자 userKey의 계좌 목록에서만 고르게 해서(10/30 결정) 남의 계좌가 끼어들 수 없다.
 *
 * <p>SSAFY 응답 코드 중 사용자가 고칠 수 있는 것(계좌번호 오류, 인증코드 불일치·만료)만 YAMU 에러 코드로 바꾸고,
 * 나머지는 금융망 실패(502·504) 그대로 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsafyAccountApi {

    static final String LIST_PATH = "/edu/demandDeposit/inquireDemandDepositAccountList";
    static final String BALANCE_PATH = "/edu/demandDeposit/inquireDemandDepositAccountBalance";
    static final String OPEN_AUTH_PATH = "/edu/accountAuth/openAccountAuth";
    static final String CHECK_AUTH_PATH = "/edu/accountAuth/checkAuthCode";
    static final String HISTORY_PATH = "/edu/demandDeposit/inquireTransactionHistoryList";

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /** 거래내역 조회의 입금만(transactionType M), 최근 거래부터 */
    private static final String DEPOSITS = "M";
    private static final String NEWEST_FIRST = "DESC";

    /** 계좌번호 오류 */
    private static final String ACCOUNT_INVALID = "A1003";
    /** 원화 수시입출금 계좌가 아님 */
    private static final String ACCOUNT_NOT_DEMAND_DEPOSIT = "A5004";
    /** 인증코드 발급 기록 없음(A1086), 인증 시간 만료(A1087) */
    private static final Set<String> CODE_EXPIRED = Set.of("A1086", "A1087");
    /** 인증코드 불일치. 문서는 A1088, 10/27 실측은 틀린 숫자·형식 오류 모두 A1090 */
    private static final Set<String> CODE_MISMATCH = Set.of("A1088", "A1090");

    private final FinApiClient finApiClient;

    /**
     * 이 SSAFY 회원(userKey)의 수시입출금 계좌 전체. userKey 단위라 남의 계좌는 나오지 않는다(예금주 확인이 필요 없는 이유).
     * 계좌번호·은행 코드가 비어 있는 행은 연결할 수 없어 뺀다.
     */
    public List<Account> accounts(String userKey) {
        // REC가 배열이라 JsonNode로 받아 한 줄씩 꺼낸다(FinApiClient 규칙). 배열이 아니면 응답이 깨진 것으로 본다.
        JsonNode rec = finApiClient.post(LIST_PATH, userKey, null, JsonNode.class);
        if (!rec.isArray()) {
            throw new BusinessException(ErrorCode.FIN_API_UNAVAILABLE);
        }
        List<Account> accounts = new ArrayList<>();
        for (JsonNode row : rec) {
            String accountNo = text(row, "accountNo");
            String bankCode = text(row, "bankCode");
            if (accountNo != null && bankCode != null) {
                accounts.add(new Account(bankCode, text(row, "bankName"), accountNo, text(row, "accountName")));
            }
        }
        return accounts;
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

    /**
     * 시연용: 오늘(한국 날짜) 이 계좌에 들어온 1원의 인증 숫자를 거래내역에서 찾는다(S15P31E103-350).
     * 1원 송금 응답에는 숫자가 없어서, 통장에 찍힌 적요 "{authText} {숫자}"를 최근 입금부터 본다.
     *
     * <p>찾지 못하거나 조회가 실패해도 1원 인증 자체는 계속되므로 예외를 던지지 않고 empty를 돌려준다.
     * 숫자는 로그에 남기지 않는다.
     */
    public Optional<String> findOneWonCode(String userKey, String accountNo, String authText, int codeLength,
            LocalDate today) {
        Pattern code = Pattern.compile("^" + Pattern.quote(authText) + "\\s*(\\d{" + codeLength + "})$");
        History history;
        try {
            history = finApiClient.post(HISTORY_PATH, userKey, new HistoryRequest(accountNo, today.format(DATE),
                    today.format(DATE), DEPOSITS, NEWEST_FIRST), History.class);
        } catch (RuntimeException e) {
            log.warn("1원 인증 숫자를 찾으려던 거래내역 조회 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (history == null || history.list() == null) {
            return Optional.empty();
        }
        for (HistoryRow row : history.list()) {
            for (String text : new String[] {row.transactionSummary(), row.transactionMemo()}) {
                if (text == null) {
                    continue;
                }
                Matcher matcher = code.matcher(text.strip());
                if (matcher.matches()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }

    /** 현재 잔액(원). SSAFY는 문자열("1250000")로 준다. */
    public long balance(String userKey, String accountNo) {
        return finApiClient.post(BALANCE_PATH, userKey, Map.of("accountNo", accountNo), Balance.class).accountBalance();
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
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

    /** SSAFY 계좌 목록 한 줄. 금액 등 다른 칸은 쓰지 않는다(잔액은 1원 인증 뒤 잔액 조회로). */
    public record Account(String bankCode, String bankName, String accountNo, String accountName) {
    }

    record CheckResult(String status) {
    }

    /** 거래내역 조회 요청. 금융망 호출 로그에 계좌번호 전체가 남지 않게 끝 4자리만 보인다. */
    record HistoryRequest(String accountNo, String startDate, String endDate, String transactionType,
            String orderByType) {

        @Override
        public String toString() {
            String last4 = accountNo == null || accountNo.length() < 4 ? "****" : accountNo.substring(accountNo.length() - 4);
            return "HistoryRequest[accountNo=****" + last4 + ", startDate=" + startDate + ", endDate=" + endDate
                    + ", transactionType=" + transactionType + ", orderByType=" + orderByType + "]";
        }
    }

    /** 거래내역 중 1원 숫자를 찾는 데 필요한 칸만 읽는다. */
    record History(List<HistoryRow> list) {
    }

    /** 적요·메모에 인증 숫자가 있으므로 로그에 남기지 않는다. */
    record HistoryRow(String transactionSummary, String transactionMemo) {

        @Override
        public String toString() {
            return "HistoryRow[...]";
        }
    }

    record Balance(@NotNull Long accountBalance) {
    }
}
