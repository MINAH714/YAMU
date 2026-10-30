package com.yamu.be.card;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 카드 등록 정책({@code yamu.card-register.*}). 기본값은 노션 명세 「카드 확인」「카드 등록」 기준이다.
 *
 * @param verificationTtl  카드 확인 결과(verificationToken) 유효 시간(5분). 등록에 한 번만 쓸 수 있다
 * @param lookupPerMinute 사용자별 카드 확인 호출 한도(1분, 구간은 yamu.auth.rate-limit.window). 카드번호 대입을 막는다
 */
@Validated
@ConfigurationProperties(prefix = "yamu.card-register")
public record CardRegisterProperties(
        @NotNull Duration verificationTtl,
        @NotNull @Min(1) Integer lookupPerMinute) {
}
