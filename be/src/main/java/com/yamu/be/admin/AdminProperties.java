package com.yamu.be.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 API 설정({@code yamu.admin.*}).
 *
 * @param key 운영 API가 받는 {@code X-Admin-Key}. 운영은 환경변수 {@code YAMU_ADMIN_KEY}, 로컬은 application-local.yml.
 *            비어 있으면 운영 API를 쓸 수 없다(모두 403)
 */
@ConfigurationProperties(prefix = "yamu.admin")
public record AdminProperties(String key) {

    public boolean hasKey() {
        return key != null && !key.isBlank();
    }
}
