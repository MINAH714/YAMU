package com.yamu.be.global.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 조회 해시와 PIN pepper가 같이 쓰는 HMAC-SHA256. Mac은 스레드 안전하지 않아 호출마다 새로 만든다. */
final class Hmacs {

    static final String ALGORITHM = "HmacSHA256";

    private Hmacs() {
    }

    static SecretKeySpec key(byte[] raw) {
        return new SecretKeySpec(raw, ALGORITHM);
    }

    static byte[] sha256(SecretKeySpec key, byte[] input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 계산하지 못했습니다.", e);
        }
    }
}
