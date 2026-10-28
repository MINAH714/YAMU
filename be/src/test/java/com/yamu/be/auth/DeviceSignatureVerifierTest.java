package com.yamu.be.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.DeviceKeys;
import org.junit.jupiter.api.Test;

class DeviceSignatureVerifierTest {

    private final DeviceSignatureVerifier verifier = new DeviceSignatureVerifier();
    private final DeviceKeys device = DeviceKeys.generate();

    @Test
    void 등록된_공개키의_짝인_개인키로_서명하면_통과한다() {
        assertThat(verifier.verify("EC_P256", device.publicKey(), "Zm9vYmFy", device.sign("Zm9vYmFy"))).isTrue();
    }

    @Test
    void 다른_challenge의_서명이면_실패한다() {
        assertThat(verifier.verify("EC_P256", device.publicKey(), "Zm9vYmFy", device.sign("other"))).isFalse();
    }

    @Test
    void 다른_기기의_개인키로_서명하면_실패한다() {
        DeviceKeys attacker = DeviceKeys.generate();

        assertThat(verifier.verify("EC_P256", device.publicKey(), "Zm9vYmFy", attacker.sign("Zm9vYmFy"))).isFalse();
    }

    @Test
    void 형식이_틀린_키나_서명은_예외없이_실패한다() {
        assertThat(verifier.verify("EC_P256", "not-a-key", "Zm9vYmFy", device.sign("Zm9vYmFy"))).isFalse();
        assertThat(verifier.verify("EC_P256", device.publicKey(), "Zm9vYmFy", "!!!")).isFalse();
        assertThat(verifier.verify("EC_P256", device.publicKey(), "Zm9vYmFy", null)).isFalse();
    }

    @Test
    void 지원하지_않는_알고리즘이면_실패한다() {
        assertThat(verifier.verify("RSA_2048", device.publicKey(), "Zm9vYmFy", device.sign("Zm9vYmFy"))).isFalse();
    }
}
