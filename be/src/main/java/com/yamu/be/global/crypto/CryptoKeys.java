package com.yamu.be.global.crypto;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link CryptoProperties}의 Base64 키를 검증하고 바이트 키로 바꿔 들고 있는다.
 *
 * <p>키가 비었거나 길이가 틀리면 서버가 뜨지 않게 생성자에서 바로 실패한다. 운영에 키 환경변수를 넣지 않고
 * 배포했을 때 첫 가입 요청에서야 오류를 보는 일을 막기 위해서다.
 */
public final class CryptoKeys {

    static final int AES_KEY_BYTES = 32;
    static final int MIN_SECRET_BYTES = 32;

    private final byte currentDataKeyId;
    private final Map<Byte, SecretKey> dataKeys;
    private final byte[] hmacKey;
    private final byte[] pinPepper;

    public CryptoKeys(CryptoProperties props) {
        if (props.dataKeys() == null || props.dataKeys().isEmpty()) {
            throw new IllegalStateException("yamu.crypto.data-keys가 비어 있습니다. 데이터 키를 설정하세요.");
        }
        Map<Byte, SecretKey> keys = new HashMap<>();
        props.dataKeys().forEach((id, base64) -> {
            if (id == null || id < 1 || id > 255) {
                throw new IllegalStateException("데이터 키 번호는 1~255여야 합니다: " + id);
            }
            byte[] raw = decode(base64, "yamu.crypto.data-keys[" + id + "]");
            if (raw.length != AES_KEY_BYTES) {
                throw new IllegalStateException("데이터 키 " + id + "는 32바이트(AES-256)여야 합니다. 현재 " + raw.length + "바이트");
            }
            keys.put((byte) id.intValue(), new SecretKeySpec(raw, "AES"));
        });
        if (!props.dataKeys().containsKey(props.dataKeyId())) {
            throw new IllegalStateException("yamu.crypto.data-key-id(" + props.dataKeyId() + ")에 해당하는 데이터 키가 없습니다.");
        }
        this.currentDataKeyId = (byte) props.dataKeyId();
        this.dataKeys = Map.copyOf(keys);
        this.hmacKey = requireSecret(props.hmacKey(), "yamu.crypto.hmac-key");
        this.pinPepper = requireSecret(props.pinPepper(), "yamu.crypto.pin-pepper");
        requireDistinct();
    }

    /** 키를 용도별로 나눈 이유(하나가 새도 다른 용도는 안전)가 같은 값을 붙여 넣는 실수로 사라지지 않게 막는다. */
    private void requireDistinct() {
        Set<String> seen = new HashSet<>();
        List<byte[]> all = new ArrayList<>();
        dataKeys.values().forEach(k -> all.add(k.getEncoded()));
        all.add(hmacKey);
        all.add(pinPepper);
        for (byte[] key : all) {
            if (!seen.add(Base64.getEncoder().encodeToString(key))) {
                throw new IllegalStateException("yamu.crypto의 키는 데이터 키·hmac-key·pin-pepper가 모두 서로 달라야 합니다.");
            }
        }
    }

    byte currentDataKeyId() {
        return currentDataKeyId;
    }

    SecretKey currentDataKey() {
        return dataKeys.get(currentDataKeyId);
    }

    /** 봉투에 적힌 키 번호의 키. 없으면 설정에서 옛 키를 지운 것이다. */
    SecretKey dataKey(byte id) {
        SecretKey key = dataKeys.get(id);
        if (key == null) {
            throw new IllegalStateException("봉투의 데이터 키 번호 " + Byte.toUnsignedInt(id) + "가 설정에 없습니다.");
        }
        return key;
    }

    byte[] hmacKey() {
        return hmacKey.clone();
    }

    byte[] pinPepper() {
        return pinPepper.clone();
    }

    private static byte[] requireSecret(String base64, String name) {
        byte[] raw = decode(base64, name);
        if (raw.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(name + "는 32바이트 이상이어야 합니다. 현재 " + raw.length + "바이트");
        }
        return raw;
    }

    private static byte[] decode(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(name + "가 비어 있습니다. 운영은 환경변수, 로컬은 application.yml local 프로필 값을 확인하세요.");
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + "가 Base64 형식이 아닙니다.", e);
        }
    }
}
