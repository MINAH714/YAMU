package com.yamu.be.account.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 연결 계좌 목록(명세 「계좌 목록 조회」). 연결한 순서.
 *
 * @param dataSyncedAt 화면에 보이는 잔액 중 가장 오래된 조회 시각(UTC). 잔액을 하나도 못 읽었으면 null
 */
public record AccountListResponse(List<AccountResponse> accounts, LocalDateTime dataSyncedAt) {
}
