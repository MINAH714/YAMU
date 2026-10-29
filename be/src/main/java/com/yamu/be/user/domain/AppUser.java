package com.yamu.be.user.domain;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * app_user. 휴대폰·SSAFY userKey는 암호화·HMAC 값만 들고 있다(global.crypto).
 *
 * <p>알림·잔고예보 설정(JSON 컬럼)은 해당 기능에서 매핑한다. 매핑하지 않은 컬럼은 NULL로 저장된다.
 */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(length = 100)
    private String name;

    private LocalDate birthDate;

    @Column(length = 2048)
    private byte[] phoneEnc;

    @Column(columnDefinition = "binary(32)")
    private byte[] phoneHash;

    private String pinHash;

    private LocalDateTime pinUpdatedAt;

    private LocalDateTime identityVerifiedAt;

    private String ssafyMemberId;

    @Column(length = 2048)
    private byte[] ssafyUserKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppUserStatus status;

    private LocalDateTime deletedAt;

    @Version
    private Long version;

    private AppUser(String name, LocalDate birthDate, byte[] phoneEnc, byte[] phoneHash, LocalDateTime identityVerifiedAt) {
        this.name = name;
        this.birthDate = birthDate;
        this.phoneEnc = phoneEnc;
        this.phoneHash = phoneHash;
        this.identityVerifiedAt = identityVerifiedAt;
        this.status = AppUserStatus.PENDING;
    }

    /** 본인인증을 마친 가입 진행 중(PENDING) 사용자. phoneEnc·phoneHash는 global.crypto로 만든 값이다. */
    public static AppUser pending(String name, LocalDate birthDate, byte[] phoneEnc, byte[] phoneHash,
            LocalDateTime identityVerifiedAt) {
        return new AppUser(name, birthDate, phoneEnc, phoneHash, identityVerifiedAt);
    }

    /** SSAFY 금융망 계정을 연결한다. userKeyEnc는 global.crypto로 암호화한 봉투다. */
    public void linkFinMember(String ssafyMemberId, byte[] ssafyUserKeyEnc) {
        this.ssafyMemberId = ssafyMemberId;
        this.ssafyUserKey = ssafyUserKeyEnc;
    }

    public void changePin(String pinHash, LocalDateTime now) {
        this.pinHash = pinHash;
        this.pinUpdatedAt = now;
    }

    /** ck_user_active: 본인인증·휴대폰·PIN이 모두 있어야 ACTIVE가 된다. */
    public void activate() {
        if (name == null || birthDate == null || phoneEnc == null || phoneHash == null
                || pinHash == null || pinUpdatedAt == null || identityVerifiedAt == null) {
            throw new IllegalStateException("본인인증·휴대폰·PIN이 모두 있어야 활성화할 수 있습니다.");
        }
        this.status = AppUserStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == AppUserStatus.ACTIVE;
    }
}
