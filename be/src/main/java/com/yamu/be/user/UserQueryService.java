package com.yamu.be.user;

import com.yamu.be.auth.OnboardingStatusReader;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.dto.MyInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보 조회(명세 V2 「내 정보 조회」 No.42, FR-001). 토큰의 사용자·기기 기준이라 남의 정보를 볼 경로가 없다.
 *
 * <ul>
 *   <li>초기 설정(onboarding): 로그인 응답의 onboardingCompleted와 같은 {@link OnboardingStatusReader}로 판단한다
 *       (등록된 카드 ACTIVE·INACTIVE 1장 이상 + 연결된 기준 계좌)</li>
 *   <li>보안 설정: PIN 설정 여부·생체인증·지금 기기는 실제 값. 앱 잠금·결제 코드 인증 생략 시간과 결제 코드 인증 방식은
 *       <b>임시</b> 기본값이다. 저장할 칸과 수정 API(「보안 설정 수정」 P2)가 아직 없다</li>
 *   <li>결제 설정: 기본 카드는 user_card.is_default, 카드 자동 선택(autoSelect)은 app_user.payment_auto_select.
 *       바꾸는 곳은 「결제 설정 수정」({@code PaymentSettingsService})</li>
 *   <li>명세 v0.3.1의 onboarding.step·incomeSet·recurringCount(예정 수입·고정 지출, BE 4)는 넣지 않았다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserQueryService {

    /** 임시: 앱을 떠난 뒤 다시 잠그기까지(명세 기본값). 저장 칸이 생기면 사용자 값으로 바꾼다. */
    static final int DEFAULT_AUTO_LOCK_SECONDS = 60;
    /** 임시: 카드 등록·초기 설정 중 잠금 유예(명세 기본값). */
    static final int DEFAULT_SETUP_GRACE_SECONDS = 300;
    /** 임시: 잠금 해제 뒤 결제 코드를 열 때 다시 묻지 않는 시간(명세 기본값). */
    static final int DEFAULT_BARCODE_AUTH_GRACE_SECONDS = 60;
    /** 임시: 결제 코드 인증 방식(명세 기본값). */
    static final String DEFAULT_BARCODE_AUTH_METHOD = "BIOMETRIC_OR_PIN";

    private final AppUserRepository userRepository;
    private final UserCardRepository cardRepository;
    private final OnboardingStatusReader onboardingStatusReader;
    private final UserDeviceRepository deviceRepository;
    private final PersonalDataCipher cipher;

    /** 사용자가 없거나 탈퇴했으면 401(토큰 검사 뒤 탈퇴한 경우). */
    @Transactional(readOnly = true)
    public MyInfoResponse me(AuthUser me) {
        AppUser user = userRepository.findById(me.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        OnboardingStatusReader.Status onboarding = onboardingStatusReader.status(user.getId());
        Long defaultUserCardId = cardRepository.findByUserId(user.getId()).stream()
                .filter(card -> card.getRegistrationStatus().isRegistered())
                .filter(UserCard::isDefaultCard).map(UserCard::getId)
                .findFirst().orElse(null);
        UserDevice device = currentDevice(me);

        return new MyInfoResponse(
                user.getId(),
                maskPhone(user.getPhoneEnc() == null ? null : cipher.decrypt(user.getPhoneEnc(), CipherField.APP_USER_PHONE)),
                user.getName(),
                new MyInfoResponse.Onboarding(onboarding.cardRegistered(), onboarding.primaryAccountSet(),
                        onboarding.completed()),
                new MyInfoResponse.SecuritySettings(DEFAULT_AUTO_LOCK_SECONDS, DEFAULT_SETUP_GRACE_SECONDS,
                        DEFAULT_BARCODE_AUTH_GRACE_SECONDS, DEFAULT_BARCODE_AUTH_METHOD,
                        device != null && device.isBiometricEnabled(), user.getPinHash() != null,
                        device == null ? null : new MyInfoResponse.CurrentDevice(device.getId(), device.getDeviceName())),
                new MyInfoResponse.PaymentSettings(user.isPaymentAutoSelect(), defaultUserCardId),
                user.getCreatedAt());
    }

    /** 토큰의 기기. 내 기기가 아니거나 해제됐으면 null(해제된 기기의 토큰은 보통 토큰 검사에서 먼저 막힌다). */
    private UserDevice currentDevice(AuthUser me) {
        if (me.userDeviceId() == null) {
            return null;
        }
        return deviceRepository.findById(me.userDeviceId())
                .filter(device -> device.getUser().getId().equals(me.userId()))
                .filter(device -> !device.isRevoked())
                .orElse(null);
    }

    /**
     * 가운데를 가린 휴대폰 번호. 저장값은 숫자만(PersonalDataNormalizer.phone)이다.
     * 01012345678 → 010-****-5678, 0111234567 → 011-***-4567. 형식이 다르면(옛 데이터) 끝 4자리만 남긴다.
     */
    static String maskPhone(String digits) {
        if (digits == null) {
            return null;
        }
        if (digits.length() == 11 || digits.length() == 10) {
            return digits.substring(0, 3) + "-" + "*".repeat(digits.length() - 7) + "-" + digits.substring(digits.length() - 4);
        }
        return digits.length() <= 4 ? "*".repeat(digits.length()) : "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }
}
