package com.yamu.be.account.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 연결할 수 있는 계좌 목록. 1원 인증 전이라 잔액은 보여 주지 않고, 계좌번호는 끝 4자리만 준다.
 *
 * @param expiresAt 이 목록의 candidateId를 쓸 수 있는 시각(10분)
 */
public record AccountCandidatesResponse(List<Candidate> accounts, LocalDateTime expiresAt) {

    /**
     * @param candidateId   1원 인증 요청·계좌 연결에 넘기는 값. 계좌번호 대신 쓴다
     * @param accountName   SSAFY 계좌 이름(예: "YAMU 입출금")
     * @param alreadyLinked 이미 연결한 계좌. 가입 중 목록에만 true가 섞여 나온다(고르면 연결 때 건너뜀)
     */
    public record Candidate(String candidateId, BankResponse bank, String maskedAccountNo, String accountName,
            boolean alreadyLinked) {
    }
}
