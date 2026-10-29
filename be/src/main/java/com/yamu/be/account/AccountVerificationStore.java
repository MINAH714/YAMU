package com.yamu.be.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 계좌 확인 결과(verificationToken)를 Redis에 10분 보관한다. 원문 대신 SHA-256 해시를 키로 쓴다.
 *
 * <pre>
 * 토큰: "avt_{24바이트 랜덤 Base64URL}"
 * account:verify:{sha256(토큰)} → JSON { userId, bankCode, bankName, accountNoEnc, accountNoHash, last4, authRequestedAt, codeVerified }
 * </pre>
 *
 * <p>계좌번호는 암호문으로만 둔다(Redis도 개인정보 저장소). 1원 인증 요청·계좌 연결이 같은 토큰을 쓰므로 꺼낼 때 지우지 않고,
 * 연결이 끝나면 {@link #delete}로 지운다.
 */
@Component
@RequiredArgsConstructor
public class AccountVerificationStore {

    private static final String KEY_PREFIX = "account:verify:";
    private static final String TOKEN_PREFIX = "avt_";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /** ARGV[2]가 0이면 남은 TTL 유지(KEEPTTL), 아니면 그 ms로 다시 센다. 없는 키는 만들지 않는다. */
    private static final RedisScript<Long> UPDATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if ARGV[2] == '0' then
              redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
            else
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AccountLinkProperties properties;
    private final SecureRandom random = new SecureRandom();

    public String issue(VerifiedAccount account) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String token = TOKEN_PREFIX + URL.encodeToString(buf);
        redis.opsForValue().set(key(token), write(account), properties.verificationTtl());
        return token;
    }

    /** 이 사용자가 만든 토큰만 찾는다. 없거나(만료) 남의 토큰이면 empty. */
    public Optional<VerifiedAccount> find(String token, Long userId) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(key(token));
        if (json == null) {
            return Optional.empty();
        }
        try {
            VerifiedAccount account = objectMapper.readValue(json, VerifiedAccount.class);
            return account.userId().equals(userId) ? Optional.of(account) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * 내용을 바꿔 다시 저장한다. 아직 있는 키만 바꾼다(SET XX). 만료된 뒤에 쓰면 되살아나지 않는다.
     *
     * @param extendTtl true면 유효 시간을 처음부터 다시 센다(1원 인증 요청). false면 남은 시간을 유지한다
     * @return 바꿨으면 true, 그 사이 만료됐으면 false
     */
    public boolean update(String token, VerifiedAccount account, boolean extendTtl) {
        Long updated = redis.execute(UPDATE, List.of(key(token)), write(account),
                extendTtl ? String.valueOf(properties.verificationTtl().toMillis()) : "0");
        return Long.valueOf(1).equals(updated);
    }

    public void delete(String token) {
        redis.delete(key(token));
    }

    /** 같은 토큰인지 비교할 때 쓰는 해시(Idempotency-Key 재사용 확인). 원문을 Redis에 남기지 않으려고. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String token) {
        return KEY_PREFIX + hash(token);
    }

    private String write(VerifiedAccount account) {
        try {
            return objectMapper.writeValueAsString(account);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 예금주까지 확인한 계좌.
     *
     * @param accountNoEnc    계좌번호 암호문(CipherField.LINKED_ACCOUNT_NO). SSAFY에 다시 보낼 때 복호화한다
     * @param accountNoHash   조회 해시(은행코드 + 계좌번호). 이미 연결했는지·시도 횟수 키에 쓴다
     * @param authRequestedAt 1원을 보낸 시각(epoch ms, UTC). 아직 안 보냈으면 null
     * @param codeVerified    SSAFY가 인증코드를 맞다고 했는지. 본인 인증 토큰이 없어 연결이 실패한 뒤 다시 부를 때 SSAFY를 다시 부르지 않으려고
     */
    public record VerifiedAccount(Long userId, String bankCode, String bankName, byte[] accountNoEnc,
            byte[] accountNoHash, String last4, Long authRequestedAt, boolean codeVerified) {

        VerifiedAccount withAuthRequested(long epochMillis) {
            return new VerifiedAccount(userId, bankCode, bankName, accountNoEnc, accountNoHash, last4, epochMillis, false);
        }

        VerifiedAccount withCodeVerified() {
            return new VerifiedAccount(userId, bankCode, bankName, accountNoEnc, accountNoHash, last4, authRequestedAt, true);
        }

        String accountKey() {
            return userId + ":" + HexFormat.of().formatHex(accountNoHash);
        }
    }
}
