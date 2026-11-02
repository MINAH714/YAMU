package com.yamu.be.payment.barcode;

import java.time.Instant;

/**
 * Redis에 보관하는 바코드 한 건. 시각은 모두 UTC다.
 *
 * @param paymentId POS가 스캔해 만든 결제 요청 번호. 스캔 전(ISSUED·EXPIRED)에는 null
 */
public record Barcode(String token, Long userId, BarcodeStatus status, Instant issuedAt, Instant expiresAt,
        Long paymentId) {

    public Barcode(String token, Long userId, BarcodeStatus status, Instant issuedAt, Instant expiresAt) {
        this(token, userId, status, issuedAt, expiresAt, null);
    }

    /**
     * 지금 시각 기준 상태. 저장된 상태가 ISSUED여도 만료 시각이 지났으면 EXPIRED다.
     * 만료 시각 정각부터 만료로 본다. SCANNED 이후의 만료는 결제 마감(60초)이 정하므로 여기서 바꾸지 않는다.
     */
    public BarcodeStatus statusAt(Instant now) {
        if (status == BarcodeStatus.ISSUED && !now.isBefore(expiresAt)) {
            return BarcodeStatus.EXPIRED;
        }
        return status;
    }
}
