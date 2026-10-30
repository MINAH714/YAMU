package com.yamu.be.account;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 계좌 잔액 조회 정책({@code yamu.account-balance.*}). 노션 명세 「계좌 목록 조회」·「[SSAFY] 계좌 목록·잔액·예금주 조회」 기준.
 *
 * @param cacheTtl          계좌별 잔액 캐시 시간(10분). refresh=true면 건너뛰고 새로 읽는다
 * @param maxParallel       금융망 잔액 조회를 동시에 몇 개까지 보낼지. 계좌가 많아도 금융망에 한꺼번에 몰리지 않게
 * @param overallTimeout    한 번의 조회 전체 기한. 넘으면 끝나지 않은 계좌는 결과 불명(504)으로 본다
 * @param refreshPerMinute  사용자별 refresh=true 호출 한도(1분). 넘으면 429. 캐시를 건너뛰는 호출로 금융망을 두드리지 않게
 */
@Validated
@ConfigurationProperties(prefix = "yamu.account-balance")
public record AccountBalanceProperties(
        @NotNull Duration cacheTtl,
        @Min(1) int maxParallel,
        @NotNull Duration overallTimeout,
        @Min(1) int refreshPerMinute) {
}
