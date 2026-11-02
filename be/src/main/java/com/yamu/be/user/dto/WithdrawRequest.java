package com.yamu.be.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 회원 탈퇴 요청 본문. 본문 없이 보내도 된다.
 *
 * @param reason 탈퇴 사유(선택, 통계용). 개인정보가 섞일 수 있어 서버는 저장하지 않는다
 */
public record WithdrawRequest(@Size(max = 500, message = "탈퇴 사유는 500자 이하로 적어 주세요.") String reason) {
}
