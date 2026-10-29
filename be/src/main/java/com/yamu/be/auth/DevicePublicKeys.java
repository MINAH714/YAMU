package com.yamu.be.auth;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 앱이 보낸 공개키가 서명 검증({@link DeviceSignatureVerifier})에 쓸 수 있는 EC P-256 키인지 등록 전에 확인한다.
 * 형식이 틀린 키를 저장하면 그 기기로는 로그인이 영영 안 되므로 받을 때 400으로 돌려보낸다.
 */
final class DevicePublicKeys {

    private static final ECParameterSpec P256 = p256();

    private DevicePublicKeys() {
    }

    /** Base64(X.509 DER) EC P-256 공개키가 아니면 400 INVALID_PUBLIC_KEY. */
    static void requireP256(String base64PublicKey) {
        if (!isP256(base64PublicKey)) {
            throw new BusinessException(ErrorCode.INVALID_PUBLIC_KEY);
        }
    }

    private static boolean isP256(String base64PublicKey) {
        if (base64PublicKey == null || base64PublicKey.isBlank()) {
            return false;
        }
        try {
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
            if (!(key instanceof ECPublicKey ec)) {
                return false;
            }
            ECParameterSpec params = ec.getParams();
            return params.getCurve().equals(P256.getCurve()) && params.getOrder().equals(P256.getOrder())
                    && params.getGenerator().equals(P256.getGenerator());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
