package com.yamu.be.payment.barcode;

/** 바코드 상태. 값 이름은 API 명세 V2 2장 {@code BarcodeStatus}와 같다. */
public enum BarcodeStatus {
    /** 발급됨. POS가 아직 스캔하지 않음 */
    ISSUED,
    /** POS가 스캔해 결제 요청이 생김 */
    SCANNED,
    /** 결제 완료 */
    USED,
    /** 만료 (시간 초과, 새 바코드 발급, 결제 확정 시간 초과) */
    EXPIRED
}
