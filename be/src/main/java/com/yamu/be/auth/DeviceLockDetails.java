package com.yamu.be.auth;

import com.yamu.be.auth.domain.UserDevice;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** DEVICE_LOCKED 응답의 details. 응답 시각 규칙(UTC + Z, 초 단위)에 맞춘다. 예: {"lockedUntil": "2026-10-27T08:30:00Z"} */
final class DeviceLockDetails {

    private DeviceLockDetails() {
    }

    static Map<String, Object> of(UserDevice device) {
        return Map.of("lockedUntil",
                device.getLockedUntil().truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC).toString());
    }
}
