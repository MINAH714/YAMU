package com.yamu.be.auth;

import com.yamu.be.auth.domain.StepUpPurpose;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인·본인 인증에 쓰는 1회용 challenge를 Redis에 60초 보관한다. 기기 키로 서명할 대상이다.
 *
 * <pre>
 * auth:challenge:{challengeId} → "{userDeviceId}:{purpose}:{challenge}"   TTL 60초
 * </pre>
 *
 * <p>용도(purpose)를 같이 저장해서 로그인용 challenge로 본인 인증을 하거나, 바코드용 challenge로 계좌 연결 토큰을 받는 것을 막는다.
 * 꺼낼 때 GETDEL로 지워서 같은 challenge로 두 번 검증할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class ChallengeStore {

    private static final String KEY_PREFIX = "auth:challenge:";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public IssuedChallenge issue(Long userDeviceId, StepUpPurpose purpose) {
        String challengeId = "ch_" + randomToken(16);
        String challenge = randomToken(32);
        redis.opsForValue().set(KEY_PREFIX + challengeId, userDeviceId + ":" + purpose.name() + ":" + challenge,
                properties.challengeTtl());
        return new IssuedChallenge(challengeId, challenge, properties.challengeTtl().toSeconds());
    }

    /** 한 번만 꺼낼 수 있다. 없거나(만료·이미 사용) 형식이 틀리면 empty. */
    public Optional<StoredChallenge> consume(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().getAndDelete(KEY_PREFIX + challengeId);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(":", 3);
        try {
            return Optional.of(new StoredChallenge(Long.valueOf(parts[0]), StepUpPurpose.valueOf(parts[1]), parts[2]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return URL.encodeToString(buf);
    }

    public record IssuedChallenge(String challengeId, String challenge, long expiresIn) {
    }

    public record StoredChallenge(Long userDeviceId, StepUpPurpose purpose, String challenge) {

        /** 이 기기에서 이 용도로 발급한 challenge인지. */
        public boolean isFor(Long deviceId, StepUpPurpose expectedPurpose) {
            return userDeviceId.equals(deviceId) && purpose == expectedPurpose;
        }
    }
}
