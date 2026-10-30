package com.yamu.be.payment.barcode;

import java.time.Duration;
import java.util.Optional;

/** 바코드 보관소. 서버는 Redis({@link RedisBarcodeStore})를 쓴다. */
interface BarcodeStore {

    /**
     * 새 바코드를 저장하고, 같은 사용자의 이전 바코드가 아직 ISSUED면 EXPIRED로 바꾼다.
     * 두 일은 한 번에 처리해 동시에 발급해도 ISSUED 바코드가 둘 생기지 않는다.
     *
     * @param retention 보관 시간. 지나면 바코드가 지워져 조회할 수 없다.
     * @return 같은 토큰이 이미 있으면 저장하지 않고 false
     */
    boolean issue(Barcode barcode, Duration retention);

    Optional<Barcode> find(String token);
}
