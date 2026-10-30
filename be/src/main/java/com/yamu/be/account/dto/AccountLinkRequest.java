package com.yamu.be.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 가입 중과 가입 후에 보내는 칸이 다르다. 어느 쪽인지는 토큰으로 정하고, 빠진 칸은 Service가 400으로 알린다.
 *
 * <pre>
 * 가입 중: { verificationToken, authCode }   ← 1원 인증으로 고른 계좌를 한꺼번에 연결
 * 가입 후: { candidateIds }                   ← X-Step-Up-Token으로 연결(1원 없음)
 * </pre>
 *
 * @param authCode 1원 입금자명에 붙은 숫자 4자리. 형식이 틀리면 400으로 끝나 3번의 시도 횟수를 깎지 않는다
 */
public record AccountLinkRequest(
        String verificationToken,
        @Pattern(regexp = "\\d{4}", message = "인증코드는 숫자 4자리입니다.")
        String authCode,
        @Size(max = 20, message = "계좌는 한 번에 20개까지 고를 수 있습니다.")
        List<@NotBlank(message = "계좌 선택 값이 비어 있습니다.") String> candidateIds) {
}
