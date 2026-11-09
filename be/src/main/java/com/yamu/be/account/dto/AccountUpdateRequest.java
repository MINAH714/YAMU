package com.yamu.be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * 계좌 정보 수정(명세 「계좌 정보 수정 (기준 계좌 지정)」). 보낸 필드만 바꾸고, 보내지 않았거나 null인 필드는 그대로 둔다.
 *
 * @param accountName 별칭. 빈 문자열이면 지운다
 * @param isPrimary   true면 이 계좌를 기준 계좌로(기존 기준 계좌는 풀림). false로 기준 계좌를 풀면 400 PRIMARY_REQUIRED
 */
public record AccountUpdateRequest(
        @Size(max = 20, message = "계좌 별칭은 20자까지입니다.")
        String accountName,
        @JsonProperty("isPrimary") Boolean isPrimary) {
}
