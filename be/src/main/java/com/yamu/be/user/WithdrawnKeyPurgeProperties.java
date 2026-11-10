package com.yamu.be.user;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 탈퇴 뒤 재가입 열쇠 정리(S15P31E103-380). {@code yamu.user.withdrawn-key-purge}.
 *
 * @param enabled   스케줄 실행을 켤지. 끄면 수동 실행 API로만 돈다
 * @param retention 탈퇴 뒤 재가입 열쇠를 남겨 두는 기간(임시 정책 30일)
 */
@ConfigurationProperties("yamu.user.withdrawn-key-purge")
public record WithdrawnKeyPurgeProperties(boolean enabled, Duration retention) {

    public WithdrawnKeyPurgeProperties {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("yamu.user.withdrawn-key-purge.retention은 0보다 커야 합니다");
        }
    }
}
