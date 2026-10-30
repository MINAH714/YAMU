package com.yamu.be.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param candidateIds       함께 연결할 계좌(계좌 목록의 candidateId). 기기 재등록은 하나
 * @param primaryCandidateId 1원을 받을 계좌. candidateIds 안에 있어야 한다. 처음 연결이면 기준 계좌가 된다
 */
public record OneWonAuthRequest(
        @NotEmpty(message = "연결할 계좌를 하나 이상 골라 주세요.")
        @Size(max = 20, message = "계좌는 한 번에 20개까지 고를 수 있습니다.")
        List<@NotBlank(message = "계좌 선택 값이 비어 있습니다.") String> candidateIds,
        @NotBlank(message = "1원을 받을 계좌를 골라 주세요.")
        String primaryCandidateId) {
}
