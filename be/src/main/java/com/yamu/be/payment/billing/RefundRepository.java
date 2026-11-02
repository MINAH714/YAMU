package com.yamu.be.payment.billing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentTransactionIdOrderByBillItemIdAsc(Long paymentTransactionId);
}
