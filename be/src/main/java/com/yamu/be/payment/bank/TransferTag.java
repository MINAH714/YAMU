package com.yamu.be.payment.bank;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 출금·입금 요약(transactionSummary)에 남기는 YAMU 태그. 결과를 모를 때 거래내역에서 이 태그로 거래를 찾는다.
 *
 * <pre>
 * YAMU-P{paymentId}  체크카드 결제 출금
 * YAMU-B{billId}     신용카드 청구 출금
 * YAMU-R{refundId}   신용카드 환급 입금
 * YAMU-C{paymentId}  체크카드 취소 환불 입금
 * </pre>
 *
 * <p>임시: 체크카드 환불은 결제 상태·예외 처리 문서(Q21)와 Jira를 따라 C를 쓴다. 노션 입금 명세는 R로 적혀 있어 맞춰야 한다.
 * 번호는 앞자리 0 없이 쓴다. 글자가 정확히 같아야 같은 태그로 보므로, 한 거래를 나타내는 글자는 하나뿐이어야 한다.
 */
public record TransferTag(Kind kind, long id) {

    private static final String PREFIX = "YAMU-";
    /** long 범위(19자리)를 넘는 번호는 {@link #parse}에서 거른다. */
    private static final Pattern FORMAT = Pattern.compile("YAMU-([PBRC])([1-9][0-9]{0,18})");

    public TransferTag {
        if (kind == null) {
            throw new IllegalArgumentException("태그 종류가 없습니다");
        }
        if (id <= 0) {
            throw new IllegalArgumentException("태그 번호는 1 이상이어야 합니다: " + id);
        }
    }

    public static TransferTag payment(long paymentId) {
        return new TransferTag(Kind.PAYMENT, paymentId);
    }

    public static TransferTag bill(long billId) {
        return new TransferTag(Kind.BILL, billId);
    }

    public static TransferTag refund(long refundId) {
        return new TransferTag(Kind.REFUND, refundId);
    }

    public static TransferTag checkRefund(long paymentId) {
        return new TransferTag(Kind.CHECK_REFUND, paymentId);
    }

    /** 요약 글자가 YAMU 태그 형식과 정확히 같을 때만 읽는다. "초기 입금" 같은 다른 요약은 비어 있다. */
    public static Optional<TransferTag> parse(String summary) {
        if (summary == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(summary);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TransferTag(Kind.of(matcher.group(1).charAt(0)), Long.parseLong(matcher.group(2))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 금융망에 보내는 요약 글자. 예: {@code YAMU-P1025} */
    public String value() {
        return PREFIX + kind.letter + id;
    }

    /** 이 태그를 붙이는 거래 방향. 결제·청구는 출금, 환급·환불은 입금이다. */
    public BankDirection direction() {
        return kind.direction;
    }

    @Override
    public String toString() {
        return value();
    }

    public enum Kind {
        PAYMENT('P', BankDirection.WITHDRAWAL),
        BILL('B', BankDirection.WITHDRAWAL),
        REFUND('R', BankDirection.DEPOSIT),
        CHECK_REFUND('C', BankDirection.DEPOSIT);

        private final char letter;
        private final BankDirection direction;

        Kind(char letter, BankDirection direction) {
            this.letter = letter;
            this.direction = direction;
        }

        static Kind of(char letter) {
            for (Kind kind : values()) {
                if (kind.letter == letter) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("모르는 태그 종류: " + letter);
        }
    }
}
