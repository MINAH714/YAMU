package com.yamu.be.cashflow;

import com.yamu.be.payment.domain.PaymentStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 예정 지출·실제 결제 매칭 API 응답. 필드 이름은 노션 API 명세 「매칭 확정」「매칭 상태 조회」「매칭 후보 조회」를 따른다. */
public final class PaymentMatchResponse {

    private PaymentMatchResponse() {
    }

    /** 연결된 결제가 승인 상태면 ACTIVE, 연결 뒤 결제가 취소되면 사용자가 다시 확인해야 해서 REVIEW_REQUIRED다. */
    public enum MatchStatus {
        ACTIVE, REVIEW_REQUIRED;

        static MatchStatus of(PaymentStatus payment) {
            return payment == PaymentStatus.CANCELED ? REVIEW_REQUIRED : ACTIVE;
        }
    }

    /** 연결을 막지는 않지만 사용자에게 알릴 차이. */
    public enum Warning { AMOUNT_MISMATCH, CARD_MISMATCH, DATE_FAR, MERCHANT_CATEGORY_MISMATCH }

    /** 후보로 고른 근거. */
    public enum Reason { AMOUNT_MATCH, CARD_MATCH, MERCHANT_CATEGORY_MATCH, DATE_NEAR }

    /** 연결된 회차를 잔고예보가 어떻게 다루는지. 회차는 예정 차감에서 빠지고 실제 결제만 반영된다. */
    public enum ForecastTreatment { ACTUAL_PAYMENT_ONLY }

    /** 매칭 확정 응답. matchedAt은 UTC다. */
    public record Confirmed(Long matchId, Long cashflowItemId, LocalDate occurrenceDate, Long paymentId,
            MatchStatus matchStatus, LocalDateTime matchedAt, List<Warning> warnings,
            ForecastTreatment forecastTreatment) {
    }

    /** 매칭 상태 조회 응답. 연결이 없으면 match가 null이다. */
    public record Status(Long cashflowItemId, LocalDate occurrenceDate, MatchView match) {
    }

    /** paidAmount는 결제의 승인 금액이다. */
    public record MatchView(Long matchId, Long paymentId, String merchantName, Long paidAmount,
            PaymentStatus paymentStatus, MatchStatus matchStatus, LocalDateTime matchedAt) {
    }

    /** 매칭 후보 조회 응답. scheduledDate·scheduledAmount는 회차의 실제 예정일·금액(이번 회차만 고친 값 포함)이다. */
    public record Candidates(Long cashflowItemId, LocalDate occurrenceDate, LocalDate scheduledDate,
            Long scheduledAmount, List<Candidate> candidates) {
    }

    /** amount는 승인 금액, approvedAt은 UTC 승인 시각이다. */
    public record Candidate(Long paymentId, String merchantName, Long amount, LocalDateTime approvedAt,
            Long userCardId, String cardType, List<Reason> matchReasons, List<Warning> warnings) {
    }
}
