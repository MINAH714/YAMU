package com.yamu.be.account;

/**
 * 다른 기능에 넘기는 연결 계좌 정보. 엔티티 대신 이 값을 넘겨 트랜잭션 밖에서도 안전하게 쓴다.
 *
 * @param linkedAccountId YAMU 연결 계좌 번호
 * @param bankCode        금융망 은행 코드 (예: "088")
 * @param accountLast4    화면 표시용 계좌번호 끝 4자리. 없으면 null
 */
public record LinkedAccountRef(Long linkedAccountId, String bankCode, String accountLast4) {
}
