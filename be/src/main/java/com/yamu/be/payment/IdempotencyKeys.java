package com.yamu.be.payment;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.UUID;

/**
 * {@code Idempotency-Key} 헤더 검사. 명세 1장: 결제 승인·취소, POS 결제 요청, 계좌 연결에 UUID v4가 필수다.
 * POS 결제 요청과 결제 승인이 같이 쓴다.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    /**
     * 헤더가 없거나 UUID 형식이 아니면 400 {@link ErrorCode#IDEMPOTENCY_KEY_REQUIRED}.
     * 소문자로 맞춰 돌려주므로 Redis 키·DB {@code request_key}(ascii_bin)에 그대로 쓸 수 있다.
     */
    public static String require(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String key = idempotencyKey.trim().toLowerCase();
        try {
            if (key.length() != 36 || !UUID.fromString(key).toString().equals(key)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key는 UUID 형식이어야 합니다.");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key는 UUID 형식이어야 합니다.");
        }
        return key;
    }
}
