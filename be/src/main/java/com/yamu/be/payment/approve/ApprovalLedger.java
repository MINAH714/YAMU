package com.yamu.be.payment.approve;

import com.yamu.be.payment.domain.PaymentTransaction;
import java.util.Map;

/**
 * 승인 원장 반영 포트(Task 5.2.5). 승인이 확정된 결제 행과 같은 트랜잭션 안에서 불린다.
 *
 * <p>구현이 맡는 일(명세 「결제 승인 요청」 처리 순서 5): {@code payment_benefit}, {@code payment_limit_usage}, {@code monthly_limit_usage},
 * {@code card_monthly_performance} 반영. 예외를 던지면 승인까지 함께 롤백된다.
 * 신용카드의 {@code bill_item}·{@code card_bill}은 여기가 아니라 {@code CreditBillingWriter}(Task 5.2.4)가 같은 트랜잭션에서 적는다.
 *
 * <p>구현: {@code com.yamu.be.payment.ledger.JdbcApprovalLedger}. 없으면 {@link NoopApprovalLedger}가 쓰인다.
 */
public interface ApprovalLedger {

    Recorded recordApproval(PaymentTransaction payment, ApprovalOutcome.Approved outcome);

    /**
     * 원장에 적은 것.
     *
     * @param paymentBenefitIds 혜택 번호({@code card_benefit_id}) → 적은 {@code payment_benefit} 행 번호
     * @param values            혜택 번호 → 그 행에 적은 단위와 원화 환산액(S15P31E103-580). 승인 응답이 결제 상세와 같은 원화 값을 싣는 데 쓴다
     */
    record Recorded(Map<Long, Long> paymentBenefitIds, Map<Long, Value> values) {

        public Recorded {
            paymentBenefitIds = paymentBenefitIds == null ? Map.of() : Map.copyOf(paymentBenefitIds);
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public Recorded(Map<Long, Long> paymentBenefitIds) {
            this(paymentBenefitIds, Map.of());
        }

        public static Recorded none() {
            return new Recorded(Map.of());
        }

        /**
         * @param unit      {@code payment_benefit.value_unit}(KRW·POINT)
         * @param krwAmount {@code payment_benefit.krw_amount}. 포인트 환산율이 없거나 소수가 나와 원화로 바꾸지 못했으면 null
         */
        public record Value(String unit, Long krwAmount) {}
    }
}
