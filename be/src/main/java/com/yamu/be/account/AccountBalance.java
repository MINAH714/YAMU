package com.yamu.be.account;

import java.time.LocalDateTime;

/**
 * 연결 계좌 한 개의 잔액. 계좌 목록 화면과 잔고예보(BE 4)가 같이 쓴다.
 *
 * @param balance   현재 잔액(원). null이 아니다
 * @param fetchedAt SSAFY에서 실제로 읽은 UTC 시각. 캐시에서 꺼냈으면 그 캐시를 채운 시각
 */
public record AccountBalance(Long linkedAccountId, Long balance, LocalDateTime fetchedAt) {
}
