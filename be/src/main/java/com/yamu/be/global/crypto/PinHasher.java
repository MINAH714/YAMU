package com.yamu.be.global.crypto;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 간편 비밀번호(PIN 6자리)를 {@code BCrypt(HMAC-SHA256(pepper, PIN))}로 해시해 {@code app_user.pin_hash}에 넣는다.
 *
 * <p>6자리는 경우의 수가 100만 개라 DB만 유출돼도 어떤 해시든 오프라인으로 다 시도할 수 있다.
 * DB 밖의 pepper를 먼저 섞어 DB만으로는 시도할 재료가 없게 하고, BCrypt(사람마다 다른 솔트, 일부러 느림)로 한 번 더 감싸
 * pepper까지 새도 시간이 걸리게 한다. 온라인 대입은 로그인의 5회 잠금이 막는다.
 */
public class PinHasher {

    private static final Pattern PIN = Pattern.compile("^\\d{6}$");
    private static final int BCRYPT_STRENGTH = 10;

    private final SecretKeySpec pepper;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public PinHasher(CryptoKeys keys) {
        this.pepper = Hmacs.key(keys.pinPepper());
    }

    /** 가입·PIN 변경 때. 숫자 6자리가 아니면 400 INVALID_REQUEST. */
    public String hash(String pin) {
        if (!isPinFormat(pin)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "간편 비밀번호는 숫자 6자리여야 합니다.");
        }
        return bcrypt.encode(peppered(pin));
    }

    /** 로그인·본인 인증 때. 형식이 틀리거나 저장된 해시가 없으면 false다. */
    public boolean matches(String pin, String pinHash) {
        if (!isPinFormat(pin) || pinHash == null || pinHash.isBlank()) {
            return false;
        }
        return bcrypt.matches(peppered(pin), pinHash);
    }

    private static boolean isPinFormat(String pin) {
        return pin != null && PIN.matcher(pin).matches();
    }

    private String peppered(String pin) {
        // BCrypt 입력 한도(72바이트) 안에 들도록 32바이트 결과를 Base64(44자)로 넘긴다.
        return Base64.getEncoder().encodeToString(Hmacs.sha256(pepper, pin.getBytes(StandardCharsets.UTF_8)));
    }
}
