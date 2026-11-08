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
import java.security.MessageDigest;
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

    /** 결제 카드 자동 선택(명세 「결제 설정」). 끄면 기본 카드로 결제한다. 끄려면 기본 카드가 있어야 한다. */
    @Column(name = "payment_auto_select", nullable = false)
    private boolean paymentAutoSelect = true;

    private LocalDateTime identityVerifiedAt;

    private String ssafyMemberId;

    @Column(length = 2048)
    private byte[] ssafyUserKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppUserStatus status;

    private LocalDateTime deletedAt;

    /** 탈퇴 때 남긴 이름 조회 해시. 탈퇴 행에만 있고 재가입으로 열쇠를 넘기면 비운다. */
    @Column(columnDefinition = "binary(32)")
    private byte[] withdrawnNameHash;

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

    /** 시드 사용자가 휴대폰 본인인증을 마쳤다. ACTIVE가 되려면 이 시각이 있어야 한다(ck_user_active). */
    public void markIdentityVerified(LocalDateTime now) {
        this.identityVerifiedAt = now;
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

    /** 결제 카드 자동 선택을 켜거나 끈다. 끌 때 기본 카드가 있는지는 부르는 쪽(PaymentSettingsService)이 확인한다. */
    public void changePaymentAutoSelect(boolean on) {
        this.paymentAutoSelect = on;
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

    public boolean isWithdrawn() {
        return status == AppUserStatus.WITHDRAWN;
    }

    /** 탈퇴한 행이고 남긴 이름 해시가 같은지. 시드 사용자의 이름 비교와 같은 역할이다. */
    public boolean isWithdrawnWithName(byte[] nameHash) {
        return isWithdrawn() && withdrawnNameHash != null && nameHash != null
                && MessageDigest.isEqual(withdrawnNameHash, nameHash);
    }

    /**
     * 탈퇴. 이름·휴대폰 암호문·PIN·본인인증 시각은 비우고, 재가입 열쇠(phone_hash·birth_date·이름 해시·SSAFY 회원 ID·userKey)만
     * 남긴다. 재가입하면 열쇠는 새 사용자로 옮겨지고 이 행에서는 {@link #releaseRejoinKeys()}로 지워진다.
     * 행 자체는 거래·청구·감사 로그가 가리키므로(FK RESTRICT) 지우지 않는다.
     *
     * @param nameHash 지우기 전 이름의 조회 해시(LookupHasher.name). 재가입 때 이름까지 맞아야 이어받게 한다
     */
    public void withdraw(LocalDateTime now, byte[] nameHash) {
        this.status = AppUserStatus.WITHDRAWN;
        this.deletedAt = now;
        this.withdrawnNameHash = nameHash;
        this.name = null;
        this.phoneEnc = null;
        this.pinHash = null;
        this.pinUpdatedAt = null;
        this.identityVerifiedAt = null;
    }

    /** 재가입으로 열쇠를 새 사용자에게 넘긴 뒤 이 행에 남은 식별 값을 지운다. UNIQUE(phone_hash, birth_date)·(ssafy_member_id)를 비워 준다. */
    public void releaseRejoinKeys() {
        this.phoneHash = null;
        this.birthDate = null;
        this.withdrawnNameHash = null;
        this.ssafyMemberId = null;
        this.ssafyUserKey = null;
    }
}
