package com.yamu.be.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계좌 잔액 조회 정책({@code yamu.account-balance.*}). 노션 명세 「계좌 목록 조회」·「[SSAFY] 계좌 목록·잔액·예금주 조회」 기준.
 *
 * @param cacheTtl 계좌별 잔액 캐시 시간(10분). refresh=true면 건너뛰고 새로 읽는다
 */
@ConfigurationProperties(prefix = "yamu.account-balance")
public record AccountBalanceProperties(Duration cacheTtl) {
}
