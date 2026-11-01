package com.yamu.be.payment.bank;

import java.time.LocalDate;

/**
 * 출금·입금 한 번의 결과. 돈이 움직였는지에 따라 호출하는 쪽의 다음 행동이 달라서 예외 대신 값으로 돌려준다.
 *
 * @param status            결과 네 가지
 * @param bankTransactionNo SSAFY 거래 번호(REC.transactionUniqueNo). 성공일 때만 있다
 * @param transactionDate   SSAFY 서버가 찍은 거래일(한국 날짜). 성공일 때만 있다
 * @param rejectReason      거절 사유. 거절일 때만 있다
 * @param finCode           금융망 응답 코드(예: A1014, H1007). 운영 기록용이며 앱 응답에 내보내지 않는다. 코드가 없으면 null
 */
public record TransferResult(Status status, String bankTransactionNo, LocalDate transactionDate,
        RejectReason rejectReason, String finCode) {

    public enum Status {

        /** 돈이 움직였다. */
        SUCCESS,

        /** 금융망이 처리하지 않았다고 답했다. 돈은 움직이지 않았다. */
        REJECTED,

        /**
         * 요청은 갔지만 처리됐는지 모른다(응답 시간 초과, 서버 오류, 깨진 응답, 요청 번호 중복 H1007).
         * 같은 번호로도 새 번호로도 바로 다시 보내지 말고, {@link SsafyTransferApi#findByTag}로 거래내역에서 확인한다.
         */
        UNKNOWN,

        /** 요청이 금융망에 가지 않았다(연결 실패). 돈은 움직이지 않았고 같은 번호로 다시 보내도 된다. */
        NOT_SENT
    }

    public enum RejectReason {

        /** A1014. 체크카드 결제는 DECLINED(INSUFFICIENT_BALANCE), 청구 출금은 UNPAID로 이어진다. */
        INSUFFICIENT_BALANCE,

        /** A1003. 해지 등으로 계좌가 없다. 연결 계좌를 사용할 수 없게 바꾸는 일은 호출하는 쪽에서 한다. */
        ACCOUNT_NOT_FOUND,

        /** 그 밖의 거절 코드. {@link #finCode()}를 본다. */
        OTHER
    }

    static TransferResult success(String bankTransactionNo, LocalDate transactionDate) {
        return new TransferResult(Status.SUCCESS, bankTransactionNo, transactionDate, null, null);
    }

    static TransferResult rejected(RejectReason reason, String finCode) {
        return new TransferResult(Status.REJECTED, null, null, reason, finCode);
    }

    static TransferResult unknown(String finCode) {
        return new TransferResult(Status.UNKNOWN, null, null, null, finCode);
    }

    static TransferResult notSent() {
        return new TransferResult(Status.NOT_SENT, null, null, null, null);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
