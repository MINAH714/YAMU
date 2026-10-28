package com.yamu.be.auth.domain;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** user_device_event. 기기 등록·키 교체·잠금·해제 이력. 한 번 쓰고 바꾸지 않는다. */
@Entity
@Table(name = "user_device_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDeviceEvent extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_device_event_id")
    private Long id;

    @Column(name = "user_device_id", nullable = false)
    private Long userDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeviceEventType eventType;

    /** 민감값을 뺀 사유. */
    @Column(length = 255)
    private String reason;

    public UserDeviceEvent(Long userDeviceId, DeviceEventType eventType, String reason) {
        this.userDeviceId = userDeviceId;
        this.eventType = eventType;
        this.reason = reason;
    }
}
