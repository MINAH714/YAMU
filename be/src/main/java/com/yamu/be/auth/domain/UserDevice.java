package com.yamu.be.auth.domain;

import com.yamu.be.global.entity.BaseTimeEntity;
import com.yamu.be.user.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_device. 로그인 기기와 서명 검증용 공개키, 연속 실패 횟수·잠금을 들고 있다.
 *
 * <ul>
 *   <li>{@code publicKey}(기기 키): 지문 없이 서명. PIN 로그인에서 "등록된 그 폰"을 증명한다. 모든 기기 필수.</li>
 *   <li>{@code biometricPublicKey}(생체 키): 지문을 찍어야 서명. 생체 로그인에서 "그 폰 + 그 사람"을 증명한다.</li>
 * </ul>
 */
@Entity
@Table(name = "user_device")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDevice extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_device_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, columnDefinition = "char(36)")
    private String deviceUuid;

    @Column(length = 100)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(nullable = false, columnDefinition = "text")
    private String publicKey;

    @Column(nullable = false, length = 32)
    private String keyAlgorithm;

    @Column(columnDefinition = "text")
    private String biometricPublicKey;

    @Column(nullable = false)
    private boolean biometricEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceStatus status;

    @Column(nullable = false)
    private int failedCount;

    private LocalDateTime lockedUntil;

    private LocalDateTime lastVerifiedAt;

    @Version
    private Long version;

    private UserDevice(AppUser user, String deviceUuid, String deviceName, DevicePlatform platform,
            String publicKey, String keyAlgorithm, String biometricPublicKey) {
        this.user = user;
        this.deviceUuid = deviceUuid;
        this.deviceName = deviceName;
        this.platform = platform;
        this.publicKey = publicKey;
        this.keyAlgorithm = keyAlgorithm;
        this.biometricPublicKey = biometricPublicKey;
        this.biometricEnabled = biometricPublicKey != null;
        this.status = DeviceStatus.ACTIVE;
    }

    /** 생체를 쓰지 않으면 biometricPublicKey는 null이다. */
    public static UserDevice register(AppUser user, String deviceUuid, String deviceName, DevicePlatform platform,
            String publicKey, String keyAlgorithm, String biometricPublicKey) {
        return new UserDevice(user, deviceUuid, deviceName, platform, publicKey, keyAlgorithm, biometricPublicKey);
    }

    public boolean isRevoked() {
        return status == DeviceStatus.REVOKED;
    }

    /** 로그인·재발급에 쓸 수 있는 기기인지: 해제되지 않았고 주인이 활성 사용자. 잠금은 시각을 봐야 해서 따로 {@link #isLockedAt}. */
    public boolean isUsable() {
        return !isRevoked() && user.isActive();
    }

    /** 잠금 중인지. 잠금 시각이 지났으면 아직 LOCKED 상태여도 잠금 중이 아니다(다음 시도 때 {@link #unlockIfExpired}). */
    public boolean isLockedAt(LocalDateTime now) {
        return status == DeviceStatus.LOCKED && lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 잠금 시각이 지났으면 풀고 true. */
    public boolean unlockIfExpired(LocalDateTime now) {
        if (status != DeviceStatus.LOCKED || isLockedAt(now)) {
            return false;
        }
        this.status = DeviceStatus.ACTIVE;
        this.failedCount = 0;
        this.lockedUntil = null;
        return true;
    }

    /** 실패를 하나 세고, 한도에 닿으면 잠근 뒤 true. */
    public boolean recordFailure(LocalDateTime now, int maxFailures, Duration lockDuration) {
        this.failedCount++;
        if (failedCount >= maxFailures) {
            this.status = DeviceStatus.LOCKED;
            this.lockedUntil = now.plus(lockDuration);
            return true;
        }
        return false;
    }

    public void recordSuccess(LocalDateTime now) {
        this.failedCount = 0;
        this.lastVerifiedAt = now;
    }

    public void revoke() {
        this.status = DeviceStatus.REVOKED;
    }

    /**
     * 같은 폰(deviceUuid)을 다시 등록할 때 키를 바꾼다. 앱을 다시 깔지 않고 키만 새로 만든 경우·기기 재등록·생체 켜기·끄기.
     * 본인 확인을 다시 마친 뒤에만 부르므로 해제·잠금도 풀고 실패 횟수를 0으로 되돌린다.
     */
    public void replaceKeys(String deviceName, DevicePlatform platform, String publicKey, String keyAlgorithm,
            String biometricPublicKey) {
        this.deviceName = deviceName;
        this.platform = platform;
        this.publicKey = publicKey;
        this.keyAlgorithm = keyAlgorithm;
        this.biometricPublicKey = biometricPublicKey;
        this.biometricEnabled = biometricPublicKey != null;
        this.status = DeviceStatus.ACTIVE;
        this.failedCount = 0;
        this.lockedUntil = null;
    }
}
