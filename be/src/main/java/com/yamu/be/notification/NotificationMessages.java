package com.yamu.be.notification;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 알림 제목·내용·중복 방지 키·앱 이동 대상(Task 9.3.2). 앱이 {@code refType}으로 화면을 고르는 값(앱 {@code MyModels.kt}):
 * {@code PAYMENT_TRANSACTION} → 결제 상세, {@code CARD_BILL}·{@code BALANCE} → 잔고예보.
 *
 * <p>중복 방지 키는 "같은 사건이면 같은 값"이다. 결제는 결제 번호+결과, 청구는 청구서 번호, 환급은 환급 번호, 잔고 위험은 위험 구간 첫날이다.
 * 제목·내용은 만들 때의 값으로 고정된다(목록 API 문서와 같다).
 */
final class NotificationMessages {

    static final String PAYMENT_TRANSACTION = "PAYMENT_TRANSACTION";
    static final String CARD_BILL = "CARD_BILL";
    static final String BALANCE = "BALANCE";

    private NotificationMessages() {
    }

    static NotificationWriter.Draft paymentApproved(long userId, long paymentId,
            NotificationContentReader.Payment payment) {
        String body = payment.merchantName() + " " + won(payment.requestedAmount()) + " 결제가 승인됐어요";
        if (payment.cardName() != null) {
            body = payment.merchantName() + " " + won(payment.requestedAmount()) + "을 " + payment.cardName()
                    + "(으)로 결제했어요";
        }
        Long approved = payment.approvedAmount();
        if (approved != null && approved < payment.requestedAmount()) {
            body += " (즉시할인 " + won(payment.requestedAmount() - approved) + ")";
        }
        return new NotificationWriter.Draft(userId, NotificationType.PAYMENT_RESULT,
                "PAYMENT_RESULT:" + paymentId + ":APPROVED", "결제 승인", body, PAYMENT_TRANSACTION, paymentId);
    }

    static NotificationWriter.Draft paymentCanceled(long userId, long paymentId,
            NotificationContentReader.Payment payment) {
        String body = payment.merchantName() + " " + won(payment.requestedAmount()) + " 결제가 취소됐어요";
        return new NotificationWriter.Draft(userId, NotificationType.PAYMENT_RESULT,
                "PAYMENT_RESULT:" + paymentId + ":CANCELED", "결제 취소", body, PAYMENT_TRANSACTION, paymentId);
    }

    /**
     * @param settledWithoutWithdrawal 확정액이 0원이라 출금 없이 닫혔는지
     */
    static NotificationWriter.Draft billConfirmed(long userId, long cardBillId, NotificationContentReader.Bill bill,
            long confirmedAmount, LocalDate withdrawalDate, boolean settledWithoutWithdrawal) {
        String title = bill.cardName() + " " + bill.billingMonth().getMonthValue() + "월 청구 확정";
        String body = settledWithoutWithdrawal
                ? "이번 청구액이 0원이라 출금 없이 마감됐어요"
                : won(confirmedAmount) + "이 " + date(withdrawalDate) + " 결제 계좌에서 출금될 예정이에요";
        return new NotificationWriter.Draft(userId, NotificationType.BILL_CONFIRMED, "BILL_CONFIRMED:" + cardBillId,
                title, body, CARD_BILL, cardBillId);
    }

    static NotificationWriter.Draft billWithdrawn(long userId, long cardBillId, NotificationContentReader.Bill bill,
            long amount, LocalDate withdrawnOn) {
        String body = bill.cardName() + " " + bill.billingMonth().getMonthValue() + "월 청구액 " + won(amount) + "이 "
                + date(withdrawnOn) + "에 출금됐어요";
        return new NotificationWriter.Draft(userId, NotificationType.BILL_WITHDRAWN, "BILL_WITHDRAWN:" + cardBillId,
                "카드 대금 출금 완료", body, CARD_BILL, cardBillId);
    }

    /**
     * @param reason 은행 거절 사유 이름(INSUFFICIENT_BALANCE·ACCOUNT_NOT_FOUND·그 밖)
     */
    static NotificationWriter.Draft billUnpaid(long userId, long cardBillId, NotificationContentReader.Bill bill,
            long unpaidAmount, String reason) {
        String why = switch (reason) {
            case "INSUFFICIENT_BALANCE" -> "결제 계좌 잔액이 부족해";
            case "ACCOUNT_NOT_FOUND" -> "결제 계좌를 찾을 수 없어";
            default -> "은행에서 출금이 거절돼";
        };
        String body = why + " " + bill.cardName() + " " + bill.billingMonth().getMonthValue() + "월 청구액 "
                + won(unpaidAmount) + "을 출금하지 못했어요";
        return new NotificationWriter.Draft(userId, NotificationType.BILL_UNPAID, "BILL_UNPAID:" + cardBillId,
                "카드 대금 미납", body, CARD_BILL, cardBillId);
    }

    /**
     * @param payment 환급의 원래 결제. 읽지 못했으면 null(가맹점 없이 쓴다)
     */
    static NotificationWriter.Draft refundCompleted(long userId, long refundId, long paymentId,
            NotificationContentReader.Payment payment, long amount) {
        String body = (payment == null ? "취소한 결제" : payment.merchantName() + " 취소")
                + "의 환급금 " + won(amount) + "이 결제 계좌로 들어왔어요";
        return new NotificationWriter.Draft(userId, NotificationType.REFUND_COMPLETED, "REFUND_COMPLETED:" + refundId,
                "환급 입금 완료", body, PAYMENT_TRANSACTION, paymentId);
    }

    /**
     * 잔고 위험. 중복 방지 키는 위험 구간 첫날이다(2026-11-10 결정: 같은 구간은 한 번, 새 구간이면 또).
     *
     * @param accountId   앱 이동 대상 칸을 채울 계좌. 계좌 하나의 위험이면 그 계좌, 통합 위험이면 기준 계좌(먼저 고른 계좌).
     *                    앱은 {@code BALANCE}면 번호와 상관없이 잔고예보로 간다
     * @param accountRisk 통합 잔액은 괜찮고 계좌 하나가 위험한지
     */
    static NotificationWriter.Draft balanceRisk(long userId, long accountId, boolean accountRisk, LocalDate from,
            long shortfall, long threshold, LocalDate today) {
        String when = from.equals(today) ? "지금" : date(from) + "부터";
        String whose = accountRisk ? " 연결 계좌 한 곳의" : "";
        String body = when + whose + " 예상 잔액이 안전 잔액 " + won(threshold) + "보다 최대 " + won(shortfall)
                + " 부족해요. 잔고예보에서 원인을 확인해 보세요";
        return new NotificationWriter.Draft(userId, NotificationType.BALANCE_RISK, balanceRiskKey(from),
                "잔고 부족 예상", body, BALANCE, accountId);
    }

    static String balanceRiskKey(LocalDate from) {
        return "BALANCE_RISK:" + from;
    }

    /** 12,300원 */
    static String won(long amount) {
        return String.format(Locale.KOREA, "%,d원", amount);
    }

    /** 11월 25일 */
    static String date(LocalDate date) {
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
    }
}
