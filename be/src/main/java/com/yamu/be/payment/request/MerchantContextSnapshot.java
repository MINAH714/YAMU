package com.yamu.be.payment.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code payment_transaction.merchant_context_snapshot}에 JSON으로 두는 최초 거래 조건 근거.
 * 서버가 확인한 값만 담고, 이후 POS 설정이 바뀌어도 과거 결제의 조건은 이 값을 쓴다(10/26 결정).
 *
 * <p>POS 요청(pos)과 화면 조회(barcode)가 같이 쓰므로 어느 한쪽 패키지에 두지 않는다. JSON 읽기·쓰기도 여기 한 곳에서 한다.
 *
 * @param version 이 JSON 모양의 버전. 칸을 더하면 올린다
 */
public record MerchantContextSnapshot(
        int version,
        String posCode,
        String brandCode,
        String merchantName,
        String categoryCode,
        PaymentChannel channel,
        FacilityType facilityType,
        String orderNo,
        long amount,
        PaymentPlan plan,
        Integer installmentMonths) {

    public static final int CURRENT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(MerchantContextSnapshot.class);

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 결제 행의 스냅샷을 읽는다. 없거나 읽지 못하면 empty이고, 읽지 못한 경우는 로그를 남긴다. */
    public static Optional<MerchantContextSnapshot> of(PaymentTransaction payment, ObjectMapper objectMapper) {
        if (payment.getMerchantContextSnapshot() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payment.getMerchantContextSnapshot(), MerchantContextSnapshot.class));
        } catch (JsonProcessingException e) {
            log.warn("거래 조건 스냅샷을 읽지 못함 paymentId={}: {}", payment.getId(), e.getOriginalMessage());
            return Optional.empty();
        }
    }
}
