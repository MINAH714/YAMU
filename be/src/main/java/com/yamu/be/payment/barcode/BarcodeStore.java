package com.yamu.be.payment.barcode;

import java.time.Duration;
import java.util.Optional;

/** 바코드 보관소. 서버는 Redis({@link RedisBarcodeStore})를 쓴다. */
public interface BarcodeStore {

    /**
     * 새 바코드를 저장하고, 같은 사용자의 이전 바코드가 아직 ISSUED면 EXPIRED로 바꾼다.
     * 두 일은 한 번에 처리해 동시에 발급해도 ISSUED 바코드가 둘 생기지 않는다.
     *
     * @param retention 보관 시간. 지나면 바코드가 지워져 조회할 수 없다.
     * @return 같은 토큰이 이미 있으면 저장하지 않고 false
     */
    boolean issue(Barcode barcode, Duration retention);

    /** 형식이 다른 토큰은 Redis를 보지 않고 empty다. */
    Optional<Barcode> find(String token);

    /**
     * POS가 스캔해 결제 요청이 생겼다. ISSUED이고 만료 전일 때만 SCANNED로 바꾸고 결제 번호를 붙인다(한 번에 처리).
     * 같은 바코드를 동시에 스캔하면 한 건만 SCANNED를 받는다. 보관 시간은 결제 마감 이후까지 다시 늘린다.
     */
    ScanResult scan(String token, Long paymentId, Duration retention);

    /**
     * 스캔은 됐는데 결제 요청 저장이 실패했을 때 되돌린다. 그 결제 번호로 SCANNED일 때만 ISSUED로 돌린다.
     *
     * @return 되돌렸으면 true
     */
    boolean revertScan(String token, Long paymentId);

    enum ScanResult {
        SCANNED,
        /** 없는 토큰(보관 종료 포함) */
        NOT_FOUND,
        /** 발급 뒤 유효 시간이 지남 */
        EXPIRED,
        /** 이미 SCANNED 또는 USED */
        ALREADY_USED
    }
}
