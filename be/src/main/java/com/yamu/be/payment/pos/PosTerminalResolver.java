package com.yamu.be.payment.pos;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * {@code X-POS-Key}로 모의 POS를 인증하고 posCode로 시나리오를 찾는다.
 *
 * <p>키가 없거나 다르면 401 INVALID_POS_KEY. 키는 맞지만 posCode가 설정에 없으면 404 MERCHANT_NOT_FOUND다(다른 브랜드·권한 없는 시나리오).
 * 키 비교는 길이·내용에 따라 시간이 달라지지 않게 {@link MessageDigest#isEqual}로 한다.
 */
@Component
public class PosTerminalResolver {

    static final String HEADER = "X-POS-Key";

    private final PosProperties properties;

    public PosTerminalResolver(PosProperties properties) {
        this.properties = properties;
    }

    public PosProperties.Terminal resolve(String posKey, String posCode) {
        String expected = properties.key();
        if (expected == null || expected.isBlank() || posKey == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        posKey.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.INVALID_POS_KEY);
        }
        return properties.terminal(posCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND,
                        "이 POS에서 쓸 수 없는 결제 시나리오(posCode)입니다."));
    }
}
