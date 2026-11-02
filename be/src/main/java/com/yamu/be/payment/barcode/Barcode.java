package com.yamu.be.payment.barcode;

import java.time.Instant;

/** Redis에 보관하는 바코드 한 건. 시각은 모두 UTC다. */
record Barcode(String token, Long userId, BarcodeStatus status, Instant issuedAt, Instant expiresAt) {

    /**
     * 지금 시각 기준 상태. 저장된 상태가 ISSUED여도 만료 시각이 지났으면 EXPIRED다.
     * 만료 시각 정각부터 만료로 본다.
     */
    BarcodeStatus statusAt(Instant now) {
        if (status == BarcodeStatus.ISSUED && !now.isBefore(expiresAt)) {
            return BarcodeStatus.EXPIRED;
        }
        return status;
    }
}
