package com.yamu.be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.auth.domain.AuthMethod;
import java.time.LocalDateTime;

/**
 * @param balance     SSAFY 현재 잔액. 연결은 끝났는데 잔액 조회만 실패했으면 null(연결을 되돌리지 않는다)
 * @param balanceAsOf 잔액을 조회한 시각. 잔액이 null이면 null
 * @param isPrimary   기준 계좌인지. 지금은 가장 먼저 연결한 계좌
 * @param verifiedBy  연결에 쓴 본인 인증. 가입 중(본인 인증 전)에는 null
 */
public record AccountLinkResponse(Long accountId, BankResponse bank, String accountName, String maskedAccountNo,
        Long balance, LocalDateTime balanceAsOf, @JsonProperty("isPrimary") boolean isPrimary, VerifiedBy verifiedBy,
        LocalDateTime linkedAt) {

    public record VerifiedBy(AuthMethod method, Long auditLogId, LocalDateTime at) {
    }
}
