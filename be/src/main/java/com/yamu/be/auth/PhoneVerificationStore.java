package com.yamu.be.auth;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 휴대폰 본인인증(연출) 요청을 Redis에 3분 보관한다. 실제 문자는 보내지 않는다.
 *
 * <pre>
 * auth:phone-verify:{verificationId}       → Hash { name, birthDate, phoneEnc, phoneHash, code, attempts }   TTL 3분
 * auth:phone-verify:recent:{phoneHash hex} → "1"                                                          TTL 1분(재요청 간격)
 * </pre>
 *
 * <p>확인은 Lua 스크립트 하나로 한다. 맞으면 꺼내면서 지우고(한 번만 성공), 틀리면 횟수를 올리고 5회째에 지운다.
 * 따로 읽고 쓰면 동시에 들어온 확인 요청이 같은 인증번호를 두 번 통과하거나 횟수 제한을 넘길 수 있다.
 */
@Component
@RequiredArgsConstructor
public class PhoneVerificationStore {

    private static final String KEY_PREFIX = "auth:phone-verify:";
    private static final String RECENT_PREFIX = "auth:phone-verify:recent:";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern VERIFICATION_ID = Pattern.compile("pv_[A-Za-z0-9_-]{22}");

    /**
     * {"OK", 필드1, 값1, ...} 성공(지움) / {"MISMATCH", 남은 횟수} 틀림 / {"EXPIRED"} 없음.
     * 남은 횟수가 0이 되면 지운다. ARGV[1] 받은 인증번호, ARGV[2] 최대 횟수.
     */
    private static final RedisScript<List> CONFIRM = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {'EXPIRED'} end
            if redis.call('HGET', KEYS[1], 'code') == ARGV[1] then
              local fields = redis.call('HGETALL', KEYS[1])
              redis.call('DEL', KEYS[1])
              table.insert(fields, 1, 'OK')
              return fields
            end
            local left = tonumber(ARGV[2]) - redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if left <= 0 then redis.call('DEL', KEYS[1]); left = 0 end
            return {'MISMATCH', tostring(left)}
            """, List.class);

    /** Hash 저장과 TTL을 한 번에. 따로 하면 사이에서 끊겼을 때 TTL 없는 키가 남는다. ARGV[1] TTL(ms), 나머지 필드·값 */
    private static final RedisScript<Long> PUT_WITH_TTL = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], unpack(ARGV, 2))
            return redis.call('PEXPIRE', KEYS[1], ARGV[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SignupProperties properties;
    private final SecureRandom random = new SecureRandom();

    /** 같은 번호로 재요청 간격(1분) 안에 다시 오면 429 VERIFICATION_TOO_FREQUENT. details.retryAfterSeconds */
    public void claimResendSlot(byte[] phoneHash) {
        String key = RECENT_PREFIX + HexFormat.of().formatHex(phoneHash);
        Boolean claimed = redis.opsForValue().setIfAbsent(key, "1", properties.resendInterval());
        if (!Boolean.TRUE.equals(claimed)) {
            Long ttl = redis.getExpire(key);
            long retryAfter = ttl == null || ttl < 1 ? 1 : ttl;
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_FREQUENT, Map.of("retryAfterSeconds", retryAfter));
        }
    }

    public IssuedVerification issue(VerifiedIdentity identity) {
        String verificationId = "pv_" + randomToken(16);
        String code = String.format("%06d", random.nextInt(1_000_000));
        Map<String, String> fields = new HashMap<>(identity.toFields());
        fields.put("code", code);
        fields.put("attempts", "0");
        putWithTtl(redis, KEY_PREFIX + verificationId, fields, properties.verificationTtl().toMillis());
        return new IssuedVerification(verificationId, code);
    }

    /**
     * 인증번호를 확인한다. 맞으면 본인인증 정보를 돌려주고 지운다(한 번만 성공).
     * 없거나 만료면 400 VERIFICATION_EXPIRED, 틀리면 400 VERIFICATION_CODE_MISMATCH(details.remainingAttempts).
     */
    public VerifiedIdentity confirm(String verificationId, String code) {
        // 발급한 모양("pv_" + 16바이트 Base64URL)이 아니면 Redis를 보지 않는다. 경로 값을 그대로 키에 붙이면
        // "recent:..." 같은 값으로 다른 용도의 키(String)를 Hash 명령으로 건드려 500이 날 수 있다.
        if (verificationId == null || !VERIFICATION_ID.matcher(verificationId).matches()) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED);
        }
        List<?> result = redis.execute(CONFIRM, List.of(KEY_PREFIX + verificationId), code,
                String.valueOf(properties.maxCodeAttempts()));
        String outcome = result == null || result.isEmpty() ? "EXPIRED" : (String) result.get(0);
        if ("MISMATCH".equals(outcome)) {
            int remaining = Integer.parseInt((String) result.get(1));
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_MISMATCH, Map.of("remainingAttempts", remaining));
        }
        if (!"OK".equals(outcome)) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED);
        }
        Map<String, String> fields = new HashMap<>();
        List<?> pairs = new ArrayList<>(result.subList(1, result.size()));
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            fields.put((String) pairs.get(i), (String) pairs.get(i + 1));
        }
        try {
            return VerifiedIdentity.fromFields(fields);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED);
        }
    }

    static void putWithTtl(StringRedisTemplate redis, String key, Map<String, String> fields, long ttlMillis) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(ttlMillis));
        fields.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        redis.execute(PUT_WITH_TTL, List.of(key), args.toArray());
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return URL.encodeToString(buf);
    }

    /** @param code 발송한(연출) 인증번호. 응답의 demoCode로만 쓴다 */
    public record IssuedVerification(String verificationId, String code) {
    }
}
