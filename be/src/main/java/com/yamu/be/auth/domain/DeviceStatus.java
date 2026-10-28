package com.yamu.be.auth.domain;

/** user_device.status. CHECK 제약과 같은 값이다. */
public enum DeviceStatus {
    ACTIVE,
    /** 연속 실패로 잠김. locked_until이 지나면 다음 로그인 시도 때 풀린다. */
    LOCKED,
    /** 해제됨. 다시 쓰려면 기기 재등록. */
    REVOKED
}
