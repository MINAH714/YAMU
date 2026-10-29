package com.yamu.be.card;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 카드 확인 결과(verificationToken)를 Redis에 5분 보관한다. 원문 대신 SHA-256 해시를 키로 쓴다.
 *
 * <pre>
 * 토큰: "cvt_{24바이트 랜덤 Base64URL}"
 * card:verify:{sha256(토큰)} → JSON { userId, userCardId, maskedNumber }
 * </pre>
 *
 * <p>카드번호는 넣지 않는다. 마스킹한 번호(앞 4자리·끝 4자리)만 등록 응답에 쓰려고 둔다.
 * 등록은 입력·계좌·본인 인증을 확인하는 동안 {@link #find}로 보기만 하고, 바꾸기 직전에 {@link #consume}(GETDEL)으로 한 번만 꺼낸다.
 */
@Component
@RequiredArgsConstructor
public class CardVerificationStore {

    private static final String KEY_PREFIX = "card:verify:";
    private static final String TOKEN_PREFIX = "cvt_";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CardRegisterProperties properties;
    private final SecureRandom random = new SecureRandom();

    public String issue(VerifiedCard card) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String token = TOKEN_PREFIX + URL.encodeToString(buf);
        redis.opsForValue().set(key(token), write(card), properties.verificationTtl());
        return token;
    }

    /** 이 사용자가 만든 토큰만 찾는다. 지우지 않는다. 없거나(만료·이미 사용) 남의 토큰이면 empty. */
    public Optional<VerifiedCard> find(String token, Long userId) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return read(redis.opsForValue().get(key(token))).filter(c -> c.userId().equals(userId));
    }

    /** 꺼내면서 지운다(GETDEL). 동시에 두 번 등록해도 한쪽만 성공한다. 남의 토큰이면 지우지 않는다. */
    public Optional<VerifiedCard> consume(String token, Long userId) {
        if (find(token, userId).isEmpty()) {
            return Optional.empty();
        }
        return read(redis.opsForValue().getAndDelete(key(token))).filter(c -> c.userId().equals(userId));
    }

    private Optional<VerifiedCard> read(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, VerifiedCard.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(VerifiedCard card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 사용자 명의로 확인한 카드.
     *
     * @param userCardId   등록할 user_card(PENDING 또는 UNREGISTERED)
     * @param maskedNumber "9410-****-****-5678". 등록 응답에 그대로 쓴다
     */
    public record VerifiedCard(Long userId, Long userCardId, String maskedNumber) {
    }
}
