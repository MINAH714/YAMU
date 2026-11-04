package com.yamu.be.payment.billing;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentTransactionIdOrderByBillItemIdAsc(Long paymentTransactionId);

    /** 회차들의 환급. 회차당 한 행이다({@code uk_refund_item}). */
    List<Refund> findByBillItemIdIn(Collection<Long> billItemIds);
}
