package com.yamu.be.global.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 휴대폰·계좌·카드번호·SSAFY userKey를 AES-256-GCM으로 암호화해 {@code *_enc} 컬럼(VARBINARY(2048))에 넣을 봉투를 만든다.
 *
 * <pre>
 * 봉투 = [버전 1B][키 번호 1B][nonce 12B][암호문 = 평문 길이][tag 16B]   → 길이 30 + 평문 바이트
 * </pre>
 *
 * <ul>
 *   <li>nonce는 매번 SecureRandom으로 새로 뽑는다. 같은 키로 nonce가 겹치면 평문이 드러나므로 카운터를 쓰지 않는다.</li>
 *   <li>tag가 맞지 않으면(1비트라도 변조, 다른 컬럼의 AAD) 복호화가 실패한다.</li>
 *   <li>키 번호 덕분에 키를 바꿔도 옛 봉투는 옛 키로 연다.</li>
 * </ul>
 *
 * <p>같은 값도 매번 다른 봉투가 나오므로 이 값으로는 찾거나 중복을 막을 수 없다. 그 용도는 {@link LookupHasher}다.
 */
public class PersonalDataCipher {

    static final byte VERSION = 1;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    static final int HEADER_BYTES = 2;
    static final int OVERHEAD_BYTES = HEADER_BYTES + NONCE_BYTES + TAG_BITS / 8;
    /** *_enc 컬럼 길이. 넘으면 잘라 넣지 않고 거절한다. */
    static final int MAX_ENVELOPE_BYTES = 2048;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final CryptoKeys keys;
    private final SecureRandom random;

    public PersonalDataCipher(CryptoKeys keys, SecureRandom random) {
        this.keys = keys;
        this.random = random;
    }

    public byte[] encrypt(String plain, CipherField field) {
        if (plain == null) {
            throw new IllegalArgumentException("암호화할 값이 없습니다.");
        }
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        if (plainBytes.length + OVERHEAD_BYTES > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("암호화할 값이 너무 깁니다: " + plainBytes.length + "바이트");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.currentDataKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(field.aad().getBytes(StandardCharsets.UTF_8));
            byte[] cipherTextAndTag = cipher.doFinal(plainBytes);
            return ByteBuffer.allocate(HEADER_BYTES + NONCE_BYTES + cipherTextAndTag.length)
                    .put(VERSION)
                    .put(keys.currentDataKeyId())
                    .put(nonce)
                    .put(cipherTextAndTag)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("개인정보 암호화에 실패했습니다.", e);
        }
    }

    /**
     * 봉투를 연다. 형식이 틀리거나 tag가 맞지 않으면 {@link IllegalStateException}이다.
     * 사용자 입력이 아니라 DB 값이 깨졌거나 키 설정이 틀린 것이므로 500으로 처리되는 것이 맞다.
     */
    public String decrypt(byte[] envelope, CipherField field) {
        if (envelope == null || envelope.length < OVERHEAD_BYTES) {
            throw new IllegalStateException("암호문 형식이 올바르지 않습니다.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        byte version = buffer.get();
        if (version != VERSION) {
            throw new IllegalStateException("지원하지 않는 암호문 버전입니다: " + version);
        }
        byte keyId = buffer.get();
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] cipherTextAndTag = new byte[buffer.remaining()];
        buffer.get(cipherTextAndTag);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keys.dataKey(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(field.aad().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(cipherTextAndTag), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("개인정보를 복호화하지 못했습니다(변조, 다른 컬럼 값, 키 불일치).", e);
        }
    }
}
