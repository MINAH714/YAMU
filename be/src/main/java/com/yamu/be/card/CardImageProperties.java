package com.yamu.be.card;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 공개 이미지 CDN 기준 주소. S3 버킷 주소나 만료되는 서명 URL을 저장하지 않는다. */
@ConfigurationProperties(prefix = "yamu.card-image")
public record CardImageProperties(URI baseUrl) {

    public CardImageProperties {
        if (baseUrl == null || !"https".equalsIgnoreCase(baseUrl.getScheme())
                || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !baseUrl.normalize().equals(baseUrl)) {
            throw new IllegalArgumentException("카드 이미지 기준 주소는 정규화된 HTTPS 주소여야 합니다.");
        }
    }
}
