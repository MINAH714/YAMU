package com.yamu.be.payment.approve;

/**
 * 승인 거절 사유. API 명세 「결제 승인 요청」의 {@code failure.code}이자 DB {@code payment_transaction.failure_code}의 허용값이다
 * (스키마 사전: 서버가 허용값을 검증한다). 후보 없음(NO_ELIGIBLE_CARD)은 거절이 아니라 사전 검증 실패라 여기 없다.
 */
public enum FailureCode {
    INSUFFICIENT_BALANCE("연결 계좌 잔액이 부족합니다."),
    CREDIT_LIMIT_EXCEEDED("이 카드의 가용 한도를 넘었습니다."),
    CARD_SUSPENDED("카드사에서 정지한 카드입니다."),
    UNPAID_BILL("미납 청구서가 있어 결제할 수 없습니다."),
    ISSUER_DECLINED("카드사 모의 승인이 거절됐습니다."),
    /** 임시(명세 8장 Q9 제안): 출금 계좌가 없거나 해지됨(A1003)·연결 계좌 없음. 명세 failure.code 목록에 추가 필요 */
    ACCOUNT_UNAVAILABLE("출금 계좌를 쓸 수 없습니다. 계좌를 다시 연결해 주세요."),
    /** 임시(Task 5.2.3): 출금 결과를 모른 채 거래내역에도 없음. 돈은 나가지 않았고 마감 전이면 다시 승인할 수 있다. 명세에 추가 필요 */
    WITHDRAWAL_NOT_CONFIRMED("출금이 확인되지 않았습니다. 다시 시도해 주세요.");

    private final String message;

    FailureCode(String message) {
        this.message = message;
    }

    /** 앱에 보일 기본 문구. 실행 구현이 더 구체적인 문구를 주면 그쪽을 쓴다. */
    public String message() {
        return message;
    }
}
