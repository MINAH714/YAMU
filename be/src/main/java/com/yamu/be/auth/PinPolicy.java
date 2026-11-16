package com.yamu.be.auth;

import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;

/**
 * 간편 비밀번호로 정할 수 있는 값(명세 「간편 비밀번호 설정·변경」): 숫자 6자리, 같은 숫자 반복(111111)·연속 숫자(123456, 987654) 금지.
 *
 * <p>로그인·본인 인증은 형식만 보고(PinHasher.isPinFormat), 이 규칙은 새로 정할 때만 본다. 이미 쓰고 있는 PIN을 막지 않으려고.
 */
public final class PinPolicy {

    private PinPolicy() {
    }

    /** 정할 수 없는 PIN이면 400 INVALID_PIN_FORMAT. */
    public static void requireAllowed(String pin) {
        if (!PinHasher.isPinFormat(pin) || isRepeated(pin) || isSequential(pin, 1) || isSequential(pin, -1)) {
            throw new BusinessException(ErrorCode.INVALID_PIN_FORMAT);
        }
    }

    private static boolean isRepeated(String pin) {
        return pin.chars().distinct().count() == 1;
    }

    /** step이 1이면 오름차순(123456), -1이면 내림차순(654321). */
    private static boolean isSequential(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}
