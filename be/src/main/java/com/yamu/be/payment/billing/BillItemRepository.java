package com.yamu.be.payment.billing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillItemRepository extends JpaRepository<BillItem, Long> {

    List<BillItem> findByPaymentTransactionIdOrderByInstallmentNoAsc(Long paymentTransactionId);
}
