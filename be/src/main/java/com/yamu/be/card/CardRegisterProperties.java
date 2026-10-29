package com.yamu.be.card;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카드 등록 정책({@code yamu.card-register.*}). 기본값은 노션 명세 「카드 확인」「카드 등록」 기준이다.
 *
 * @param verificationTtl 카드 확인 결과(verificationToken) 유효 시간(5분). 등록에 한 번만 쓸 수 있다
 */
@ConfigurationProperties(prefix = "yamu.card-register")
public record CardRegisterProperties(Duration verificationTtl) {
}
