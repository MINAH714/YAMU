package com.yamu.be.seed.qa;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class QaAccountDeviceFixtures {
    static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    static KeyPair key() throws Exception { return key("secp256r1"); }

    static KeyPair key(String curve) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    static QaAccountDevices.Device device(int n, long userId, KeyPair pair, Instant issued) throws Exception {
        var request = new QaAccountDevices.Device("qa-550-life-" + n, userId, UUID.randomUUID().toString(),
                "Android", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]), issued.toString(), "");
        return new QaAccountDevices.Device(request.datasetKey(), request.expectedUserId(), request.deviceId(),
                request.deviceName(), request.publicKey(), request.nonce(), request.issuedAt(), sign(pair, request.challenge()));
    }

    static String sign(KeyPair pair, String challenge) throws Exception {
        var signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(challenge.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private QaAccountDeviceFixtures() {}
}
