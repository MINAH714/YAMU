package com.yamu.be.global.crypto;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;

/**
 * 휴대폰·계좌·카드번호의 조회 해시(HMAC-SHA256, 32바이트)를 만든다. {@code *_hash} 컬럼(BINARY(32))에 넣는다.
 *
 * <p>같은 값이면 항상 같은 해시가 나와 {@code WHERE phone_hash = ?}로 찾고 UNIQUE로 중복을 막을 수 있다.
 * 키 없는 SHA-256은 휴대폰 번호(약 1억 개)를 전부 미리 계산해 역추적할 수 있으므로, 서버만 아는 키를 섞는다.
 * 입력은 메서드 안에서 {@link PersonalDataNormalizer}로 정규화하므로 호출하는 쪽은 받은 값을 그대로 넘긴다.
 */
public class LookupHasher {

    private final SecretKeySpec key;

    public LookupHasher(CryptoKeys keys) {
        this.key = Hmacs.key(keys.hmacKey());
    }

    public byte[] phone(String raw) {
        return hmac(PersonalDataNormalizer.phone(raw));
    }

    public byte[] account(String bankCode, String rawAccountNo) {
        return hmac(PersonalDataNormalizer.accountLookupKey(bankCode, rawAccountNo));
    }

    public byte[] card(String raw) {
        return hmac(PersonalDataNormalizer.cardNo(raw));
    }

    private byte[] hmac(String normalized) {
        return Hmacs.sha256(key, normalized.getBytes(StandardCharsets.UTF_8));
    }
}
