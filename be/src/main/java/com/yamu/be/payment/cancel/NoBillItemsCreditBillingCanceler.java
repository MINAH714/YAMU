package com.yamu.be.payment.cancel;

import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 임시 신용카드 회차 처리(S15P31E103-200 Merge 전). develop의 임시 신용 승인은 청구 회차를 만들지 않으므로 바꿀 회차가 없다.
 *
 * <p>회차가 있는 결제를 만나면 조용히 넘어가지 않고 실패시킨다. 회차를 그대로 두고 결제만 CANCELED로 바꾸면 취소한 결제가
 * 청구되기 때문이다. 실제 구현이 들어오면 이 클래스는 지운다.
 */
public class NoBillItemsCreditBillingCanceler implements CreditBillingCanceler {

    private final JdbcTemplate jdbc;

    public NoBillItemsCreditBillingCanceler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Result cancel(PaymentTransaction payment, LocalDateTime canceledAt) {
        Long items = jdbc.queryForObject("select count(*) from bill_item where payment_transaction_id = ?",
                Long.class, payment.getId());
        if (items != null && items > 0) {
            throw new IllegalStateException("청구 회차가 있는 결제는 회차 처리 구현 없이 취소할 수 없습니다 (결제 "
                    + payment.getId() + ")");
        }
        return Result.empty();
    }
}
