package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaAccountPreparationException.require;
import static com.yamu.be.seed.qa.QaAccountPreparationException.Code.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.auth.DeviceSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Public device proofs exported by the explicit temporary Android build. No PIN or private key fields. */
public final class QaAccountDevices {
    public static final String PREFIX = "YAMU-QA550-PREP-V1";
    public static final Set<String> DATASETS = Set.of("qa-550-life-1", "qa-550-life-2", "qa-550-life-3");
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private QaAccountDevices() {}

    public record Manifest(int version, List<Device> devices) {
        public Manifest {
            devices = devices == null ? List.of() : List.copyOf(devices);
        }

        public void validate(Clock clock, boolean fresh) {
            require(version == 1 && devices.size() == 3, INVALID_INPUT);
            Set<String> datasets = new HashSet<>(), keys = new HashSet<>(), uuids = new HashSet<>();
            Set<Long> users = new HashSet<>();
            for (Device device : devices) {
                require(device != null && DATASETS.contains(device.datasetKey()), TARGET_NOT_ALLOWED);
                device.validate(clock, fresh);
                require(datasets.add(device.datasetKey()) && keys.add(device.fingerprint())
                        && uuids.add(device.deviceId()) && users.add(device.expectedUserId()), DEVICE_CONFLICT);
            }
            require(datasets.equals(DATASETS), TARGET_NOT_ALLOWED);
        }

        public Device forDataset(String dataset) {
            return devices.stream().filter(device -> device.datasetKey().equals(dataset)).findFirst()
                    .orElseThrow(() -> new QaAccountPreparationException(TARGET_NOT_ALLOWED));
        }

        public String digest() {
            try {
                var ordered = devices.stream().sorted(java.util.Comparator.comparing(Device::datasetKey)).toList();
                return sha256(new ObjectMapper().writeValueAsBytes(new Manifest(version, ordered)));
            } catch (java.io.IOException failure) {
                throw new QaAccountPreparationException(INVALID_INPUT);
            }
        }
    }

    public record Device(String datasetKey, long expectedUserId, String deviceId, String deviceName,
                         String publicKey, String nonce, String issuedAt, String signature) {
        public String challenge() {
            return String.join("\n", PREFIX, datasetKey, Long.toString(expectedUserId), deviceId,
                    publicKey, nonce, issuedAt);
        }

        /** Stable across renewed proofs of the same device; used for idempotent registration. */
        public String bindingDigest() {
            return sha256(String.join("\n", PREFIX, datasetKey, Long.toString(expectedUserId), deviceId,
                    publicKey).getBytes(StandardCharsets.UTF_8));
        }

        public String fingerprint() {
            try {
                return sha256(Base64.getDecoder().decode(publicKey));
            } catch (RuntimeException failure) {
                throw new QaAccountPreparationException(INVALID_DEVICE_PROOF);
            }
        }

        public void validate(Clock clock, boolean fresh) {
            try {
                require(DATASETS.contains(datasetKey) && expectedUserId > 0, TARGET_NOT_ALLOWED);
                require(deviceId != null && UUID.fromString(deviceId).toString().equals(deviceId), INVALID_INPUT);
                require(deviceName != null && !deviceName.isBlank() && deviceName.length() <= 100, INVALID_INPUT);
                require(publicKey != null && publicKey.length() <= 1024 && signature != null
                        && signature.length() <= 256, INVALID_DEVICE_PROOF);
                require(nonce != null && nonce.matches("[A-Za-z0-9_-]{43}")
                        && Base64.getUrlDecoder().decode(nonce).length == 32, INVALID_DEVICE_PROOF);
                Instant issued = Instant.parse(issuedAt);
                if (fresh) require(!issued.isAfter(clock.instant().plusSeconds(120))
                        && !issued.isBefore(clock.instant().minus(MAX_AGE)), EXPIRED_DEVICE_PROOF);
                byte[] encoded = Base64.getDecoder().decode(publicKey);
                require(Base64.getEncoder().encodeToString(encoded).equals(publicKey), INVALID_DEVICE_PROOF);
                ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC")
                        .generatePublic(new X509EncodedKeySpec(encoded));
                AlgorithmParameters algorithm = AlgorithmParameters.getInstance("EC");
                algorithm.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec expected = algorithm.getParameterSpec(ECParameterSpec.class);
                ECParameterSpec actual = key.getParams();
                require(actual.getCurve().equals(expected.getCurve()) && actual.getOrder().equals(expected.getOrder())
                        && actual.getGenerator().equals(expected.getGenerator())
                        && actual.getCofactor() == expected.getCofactor(), INVALID_DEVICE_PROOF);
                require(new DeviceSignatureVerifier().verify(DeviceSignatureVerifier.EC_P256,
                        publicKey, challenge(), signature), INVALID_DEVICE_PROOF);
            } catch (QaAccountPreparationException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new QaAccountPreparationException(INVALID_DEVICE_PROOF);
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
