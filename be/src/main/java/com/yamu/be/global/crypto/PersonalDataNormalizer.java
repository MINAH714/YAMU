package com.yamu.be.global.crypto;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.regex.Pattern;

/**
 * 조회 해시·암호화 전에 값의 모양을 하나로 맞춘다.
 *
 * <p>HMAC은 글자 하나만 달라도 다른 값이 나오므로, "010-1234-5678"과 "01012345678"이 다른 사람으로 저장되지 않게
 * 가입·계좌 연결·카드 등록·시드가 모두 이 함수를 거친다. 규칙을 여기저기 다시 짜지 않는다.
 */
public final class PersonalDataNormalizer {

    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    /** 010은 항상 11자리, 옛 번호(011·016~019)만 10자리가 있다. */
    private static final Pattern MOBILE = Pattern.compile("^(010\\d{8}|01[16789]\\d{7,8})$");
    /** linked_account.bank_code(VARCHAR(20))의 금융망 은행 코드. SSAFY는 숫자 3자리지만 형식은 넓게 받는다. */
    private static final Pattern BANK_CODE = Pattern.compile("^[0-9A-Za-z]{1,20}$");

    private PersonalDataNormalizer() {
    }

    /**
     * 숫자만 남기고 국가번호 82는 국내 형식으로 바꾼다.
     * 예: "+82 10-1234-5678", "+82 010-1234-5678" → "01012345678". 휴대폰은 01로 시작하므로 82로 시작하면 국가번호다.
     */
    public static String phone(String raw) {
        String digits = digits(raw);
        if (digits.startsWith("82")) {
            String national = digits.substring(2);
            digits = national.startsWith("0") ? national : "0" + national;
        }
        if (!MOBILE.matcher(digits).matches()) {
            throw invalid("휴대폰 번호 형식이 올바르지 않습니다.");
        }
        return digits;
    }

    /** 계좌번호의 숫자만. 예: "088-822-4489693615" → "0888224489693615" */
    public static String accountNo(String raw) {
        String digits = digits(raw);
        if (digits.length() < 8 || digits.length() > 20) {
            throw invalid("계좌번호 형식이 올바르지 않습니다.");
        }
        return digits;
    }

    /** 계좌 조회 해시의 입력. 다른 은행의 같은 번호를 구분하려고 은행 코드를 앞에 붙인다. 예: "088:0888224489693615" */
    public static String accountLookupKey(String bankCode, String rawAccountNo) {
        if (bankCode == null || !BANK_CODE.matcher(bankCode).matches()) {
            throw invalid("은행 코드 형식이 올바르지 않습니다.");
        }
        return bankCode + ":" + accountNo(rawAccountNo);
    }

    /** 카드번호의 숫자만. 예: "9410-1234-1234-5678" → "9410123412345678" */
    public static String cardNo(String raw) {
        String digits = digits(raw);
        if (digits.length() < 14 || digits.length() > 19) {
            throw invalid("카드번호 형식이 올바르지 않습니다.");
        }
        return digits;
    }

    /** 이름의 앞뒤 공백만 뺀다. 본인인증 이름 비교({@code VerifiedIdentity.sameNameAs})와 같은 규칙이다. 예: " 김야무 " → "김야무" */
    public static String name(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("이름이 없습니다.");
        }
        return raw.strip();
    }

    private static String digits(String raw) {
        if (raw == null) {
            throw invalid("값이 없습니다.");
        }
        return NON_DIGIT.matcher(raw).replaceAll("");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
