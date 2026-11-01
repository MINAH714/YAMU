package com.yamu.be.payment.bank;

/**
 * 계좌 거래 방향. SSAFY는 조회 요청과 응답에서 같은 방향을 다른 글자로 쓴다.
 * 조회 요청 transactionType은 M 입금·D 출금·A 전체, 응답 목록의 transactionType은 1 입금·2 출금이다.
 */
public enum BankDirection {

    DEPOSIT("M", "1"),
    WITHDRAWAL("D", "2");

    /** 거래내역 조회 요청의 transactionType. 방향을 고르지 않으면 {@link #ALL_QUERY_CODE}. */
    static final String ALL_QUERY_CODE = "A";

    private final String queryCode;
    private final String listCode;

    BankDirection(String queryCode, String listCode) {
        this.queryCode = queryCode;
        this.listCode = listCode;
    }

    String queryCode() {
        return queryCode;
    }

    /** 거래내역 응답의 transactionType(1·2)을 방향으로 바꾼다. 그 밖의 값은 null. */
    static BankDirection fromListCode(String code) {
        for (BankDirection direction : values()) {
            if (direction.listCode.equals(code)) {
                return direction;
            }
        }
        return null;
    }
}
