package com.yamu.be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.auth.domain.AuthMethod;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 한 번에 연결한 계좌들. 잔액은 넣지 않는다(계좌 목록 조회에서 10분 캐시로 본다). 계좌마다 금융망을 부르면 연결 응답이 늦어진다.
 *
 * @param accounts   이번에 연결한 계좌(고른 순서, 1원 받은 계좌가 맨 앞)
 * @param skipped    고른 것 중 이미 연결돼 있어 건너뛴 계좌
 * @param verifiedBy 연결에 쓴 본인 인증. 가입 중(본인 인증 전)에는 null
 */
public record AccountLinkResponse(List<LinkedAccountItem> accounts, List<SkippedAccount> skipped,
        VerifiedBy verifiedBy) {

    /** @param isPrimary 기준 계좌인지. 지금은 가장 먼저 연결한 계좌 */
    public record LinkedAccountItem(Long accountId, BankResponse bank, String accountName, String maskedAccountNo,
            @JsonProperty("isPrimary") boolean isPrimary, LocalDateTime linkedAt) {
    }

    /** @param reason 지금은 ALREADY_LINKED뿐 */
    public record SkippedAccount(BankResponse bank, String maskedAccountNo, String reason) {
    }

    public record VerifiedBy(AuthMethod method, Long auditLogId, LocalDateTime at) {
    }
}
