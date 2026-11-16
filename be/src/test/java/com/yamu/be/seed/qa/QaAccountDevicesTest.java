package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaAccountDeviceFixtures.*;
import static com.yamu.be.seed.qa.QaAccountPreparationException.Code.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class QaAccountDevicesTest {
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void proofBindsDatasetUserInstallationAndPublicKey() throws Exception {
        var a = device(1, 11, key(), NOW);
        a.validate(clock, true);
        var changed = new QaAccountDevices.Device(a.datasetKey(), 12, a.deviceId(), a.deviceName(), a.publicKey(),
                a.nonce(), a.issuedAt(), a.signature());
        assertThatThrownBy(() -> changed.validate(clock, true)).hasMessage(INVALID_DEVICE_PROOF.name());
        assertThat(a.challenge()).startsWith("YAMU-QA550-PREP-V1\nqa-550-life-1\n11\n");
        assertThat(a.fingerprint()).hasSize(64);
    }

    @Test void manifestRequiresAllThreeDistinctInstallationsAndUsers() throws Exception {
        var pair = key();
        var a = device(1, 11, pair, NOW);
        var b = device(2, 12, key(), NOW);
        var c = device(3, 13, key(), NOW);
        var manifest = new QaAccountDevices.Manifest(1, List.of(a, b, c));
        manifest.validate(clock, true);
        assertThat(manifest.digest()).isEqualTo(new QaAccountDevices.Manifest(1, List.of(c, a, b)).digest());
        assertThatThrownBy(() -> new QaAccountDevices.Manifest(1, List.of(a, b)).validate(clock, true))
                .hasMessage(INVALID_INPUT.name());
        var reusedKey = device(2, 12, pair, NOW);
        assertThatThrownBy(() -> new QaAccountDevices.Manifest(1, List.of(a, reusedKey, c)).validate(clock, true))
                .hasMessage(DEVICE_CONFLICT.name());
        var reusedUser = device(2, 11, key(), NOW);
        assertThatThrownBy(() -> new QaAccountDevices.Manifest(1, List.of(a, reusedUser, c)).validate(clock, true))
                .hasMessage(DEVICE_CONFLICT.name());
    }

    @Test void newRegistrationRequiresFreshProofButRecoveryCanVerifyOldBinding() throws Exception {
        var old = device(1, 11, key(), NOW.minusSeconds(86401));
        assertThatThrownBy(() -> old.validate(clock, true)).hasMessage(EXPIRED_DEVICE_PROOF.name());
        old.validate(clock, false);
        var future = device(1, 11, key(), NOW.plusSeconds(121));
        assertThatThrownBy(() -> future.validate(clock, true)).hasMessage(EXPIRED_DEVICE_PROOF.name());
        var pair = key();
        var first = device(1, 11, pair, NOW);
        var renewed = new QaAccountDevices.Device(first.datasetKey(), first.expectedUserId(), first.deviceId(),
                first.deviceName(), first.publicKey(), first.nonce(), NOW.plusSeconds(1).toString(), "");
        assertThat(first.bindingDigest()).isEqualTo(renewed.bindingDigest());
    }

    @Test void rejectsWrongCurveAndUnlistedTargets() throws Exception {
        var wrongCurve = device(1, 11, key("secp384r1"), NOW);
        assertThatThrownBy(() -> wrongCurve.validate(clock, true)).hasMessage(INVALID_DEVICE_PROOF.name());
        var a = device(1, 11, key(), NOW);
        var other = new QaAccountDevices.Device("qa-normal-user", 11, a.deviceId(), a.deviceName(), a.publicKey(),
                a.nonce(), a.issuedAt(), a.signature());
        assertThatThrownBy(() -> other.validate(clock, true)).hasMessage(TARGET_NOT_ALLOWED.name());
    }
}
