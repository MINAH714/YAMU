package com.yamu.be.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기기 서명을 검증한다(ECDSA P-256, Java 기본 JCA). 개인키는 폰 보안 칩 밖으로 나오지 않으므로
 * 서명이 맞으면 "등록된 그 폰"에서 온 요청이다.
 *
 * <p>앱과 맞춘 형식: 공개키 Base64(X.509 SubjectPublicKeyInfo DER), 서명 Base64(DER), 서명 대상은 challenge 문자열의 UTF-8 바이트.
 */
@Slf4j
@Component
public class DeviceSignatureVerifier {

    /** user_device.key_algorithm 값 */
    public static final String EC_P256 = "EC_P256";

    public boolean verify(String keyAlgorithm, String base64PublicKey, String challenge, String base64Signature) {
        if (!EC_P256.equals(keyAlgorithm)) {
            log.warn("지원하지 않는 기기 키 알고리즘: {}", keyAlgorithm);
            return false;
        }
        if (base64PublicKey == null || challenge == null || base64Signature == null) {
            return false;
        }
        try {
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 형식이 틀린 키·서명은 위조와 같게 실패로 본다.
            return false;
        }
    }
}
