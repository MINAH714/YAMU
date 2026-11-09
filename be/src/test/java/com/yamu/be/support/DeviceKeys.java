package com.yamu.be.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * 테스트에서 폰 보안 칩 대신 쓰는 EC P-256 키 쌍. 앱과 같은 형식(공개키 X.509 Base64, 서명 DER Base64)을 만든다.
 * API를 직접 호출해 보는 테스터도 이 방식으로 서명하면 된다.
 */
public final class DeviceKeys {

    private final KeyPair keyPair;

    private DeviceKeys(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static DeviceKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return new DeviceKeys(generator.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String publicKey() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String sign(String challenge) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(challenge.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
