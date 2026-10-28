package com.yamu.be.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 challenge(서명할 일회용 문자열)를 Redis에 60초 보관한다.
 *
 * <pre>
 * auth:login-challenge:{challengeId} → "{userDeviceId}:{challenge}"   TTL 60초
 * </pre>
 *
 * 꺼낼 때 GETDEL로 읽기와 삭제를 한 명령에 한다. GET 다음 DEL로 나누면 같은 challenge로 동시에 온 두 요청이
 * 둘 다 값을 읽어 재사용 공격이 통과한다. 60초가 지나면 Redis가 지우므로 따로 지우는 작업이 없다.
 */
@Component
@RequiredArgsConstructor
public class LoginChallengeStore {

    private static final String KEY_PREFIX = "auth:login-challenge:";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public IssuedChallenge issue(Long userDeviceId) {
        String challengeId = "lc_" + randomToken(16);
        String challenge = randomToken(32);
        redis.opsForValue().set(KEY_PREFIX + challengeId, userDeviceId + ":" + challenge, properties.challengeTtl());
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
        int sep = value.indexOf(':');
        try {
            return Optional.of(new StoredChallenge(Long.valueOf(value.substring(0, sep)), value.substring(sep + 1)));
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

    public record StoredChallenge(Long userDeviceId, String challenge) {
    }
}
