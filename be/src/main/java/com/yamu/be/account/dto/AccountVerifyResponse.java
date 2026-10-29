package com.yamu.be.account.dto;

import java.time.LocalDateTime;

/**
 * @param verificationToken 1원 인증 요청·계좌 연결에 넘기는 값(10분)
 * @param maskedAccountNo   끝 4자리만. 예: "****-****-****-6547"
 * @param holderName        가입자 이름을 가린 값(예: "김*무"). SSAFY 예금주명은 계정 ID라 그대로 보여 주지 않는다
 * @param alreadyLinked     기기 재등록에서 이미 연결된 계좌를 확인했으면 true. 가입·추가 연결에서는 항상 false(연결된 계좌면 409)
 */
public record AccountVerifyResponse(String verificationToken, LocalDateTime expiresAt, BankResponse bank,
        String maskedAccountNo, String holderName, boolean holderMatched, boolean alreadyLinked) {
}
