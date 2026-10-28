package com.yamu.be.auth.domain;

/** user_device_event.event_type. 명세 2장 DeviceEventType. */
public enum DeviceEventType {
    REGISTERED,
    KEY_ROTATED,
    LOCKED,
    UNLOCKED,
    REVOKED
}
