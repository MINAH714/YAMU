package com.yamu.be.payment.result;

import com.yamu.be.payment.approve.FailureCode;

/**
 * POS 영수증에 보일 거절 문구. {@link FailureCode#message()}는 앱에서 사용자에게 하는 말("계좌를 다시 연결해 주세요")이라
 * 매장 영수증에는 맞지 않아 따로 둔다. (!95 리뷰)
 *
 * <p>출금 미확인(WITHDRAWAL_NOT_CONFIRMED)은 복구 작업이 늦게 출금을 확인하면 APPROVED로 바뀔 수 있어
 * 확정된 실패로 읽히지 않게 앱에서 결제 내역을 확인하라고 안내한다.
 */
final class PosFailureMessages {

    static final String DEFAULT = "결제가 거절됐습니다.";

    private PosFailureMessages() {
    }

    /** 사유 코드의 POS 문구. 모르는 코드(지금 enum에 없는 옛 값)는 기본 문구다. */
    static String of(String code) {
        FailureCode failureCode;
        try {
            failureCode = FailureCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
        return switch (failureCode) {
            case INSUFFICIENT_BALANCE -> "잔액 부족으로 결제가 거절됐습니다.";
            case CREDIT_LIMIT_EXCEEDED -> "카드 한도 초과로 결제가 거절됐습니다.";
            case CARD_SUSPENDED -> "사용할 수 없는 카드라 결제가 거절됐습니다.";
            case UNPAID_BILL -> "카드 미납으로 결제가 거절됐습니다.";
            case ISSUER_DECLINED -> "카드사에서 결제를 거절했습니다.";
            case ACCOUNT_UNAVAILABLE -> "출금 계좌 문제로 결제가 거절됐습니다.";
            case WITHDRAWAL_NOT_CONFIRMED -> "결제 결과를 확인하지 못했습니다. 고객 앱에서 결제 내역을 확인해 주세요.";
        };
    }
}
