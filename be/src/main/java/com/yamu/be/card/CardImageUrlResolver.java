package com.yamu.be.card;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** URL을 조합만 하며 S3·CloudFront에 요청하지 않는다. 잘못된 카탈로그 키는 이미지 없음으로 처리한다. */
@Component
public class CardImageUrlResolver {

    private static final Pattern IMAGE_KEY = Pattern.compile("cards/[A-Za-z0-9_./-]+");
    private final String baseUrl;

    public CardImageUrlResolver(CardImageProperties properties) {
        this.baseUrl = properties.baseUrl().toASCIIString().replaceAll("/+$", "");
    }

    public String resolve(String imageKey) {
        if (imageKey == null || imageKey.length() > 1024 || !IMAGE_KEY.matcher(imageKey).matches()
                || Arrays.stream(imageKey.split("/", -1))
                        .anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
            return null;
        }
        return baseUrl + "/" + imageKey;
    }
}
