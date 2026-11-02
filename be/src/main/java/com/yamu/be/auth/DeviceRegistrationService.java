package com.yamu.be.auth;

import com.yamu.be.account.AccountLinkService;
import com.yamu.be.account.AccountVerificationStore.VerifiedAccount;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.auth.domain.DeviceEventType;
import com.yamu.be.auth.domain.DevicePlatform;
import com.yamu.be.auth.domain.DeviceStatus;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceEvent;
import com.yamu.be.auth.domain.UserDeviceEventRepository;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.auth.dto.DeviceRegisterRequest;
import com.yamu.be.auth.dto.DeviceRegisterResponse;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AccessTokenIssuer;
import com.yamu.be.global.security.AccessTokenIssuer.IssuedAccessToken;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기기 등록(명세 「기기 등록」, DEC-001·POL-010). 앱이 Android Keystore에서 만든 공개키만 받는다.
 *
 * <pre>
 * 가입 완료  : Bearer signupToken → 이 세션에서 1원 인증·PIN을 마친 PENDING 사용자 → 기기 저장 + ACTIVE + 로그인 토큰
 * 기기 재등록: Bearer signupToken + 연결 계좌의 1원 인증(verificationToken·authCode) → 맡겨 둔 새 PIN 저장 + 기기 저장
 *             + 로그인 토큰, 다른 기기는 모두 해제(REVOKED + Refresh Token 폐기)
 * 가입 후    : Bearer accessToken + X-Step-Up-Token(SECURITY_SETTINGS) → 지금 기기의 키 교체(생체 켜기·끄기), 토큰 없음
 * </pre>
 *
 * <ul>
 *   <li>같은 deviceId(같은 폰)를 다시 등록하면 새 행을 만들지 않고 키를 바꾼다(KEY_ROTATED). 해제했던 폰이면 다시 살린다(REGISTERED)</li>
 *   <li>쓸 수 있는(해제되지 않은) 기기는 사용자당 1대다(2026-11-02 결정). 가입은 0대에서 시작하고, 가입 후에는 같은 폰의 키 교체만
 *       되며, 기기 재등록은 다른 기기를 모두 해제하므로 정상 흐름에서는 2대가 되지 않는다. 그래도 사용자 행을 잠근 채 세어
 *       넘으면 409 DEVICE_LIMIT_EXCEEDED로 막는다(버그로 2대가 생기지 않게 하는 안전장치)</li>
 *   <li>기기 재등록은 은행 앱의 "기기 변경"처럼 다른 기기를 모두 해제한다. 잃어버렸거나 남이 쓰고 있을 수 있는 기기를 끊는다
 *       (10/30 결정 7 변경). 해제할 기기 행도 잠가 그 기기의 로그인과 줄을 세운다. 대수는 해제한 뒤에 센다</li>
 *   <li>1원 인증코드 확인(금융망 호출)은 DB 트랜잭션 밖에서 한다. 재등록의 확인 결과는 한 번만 쓴다(같은 토큰 동시 요청은 하나만 통과)</li>
 *   <li>재등록에 기존 PIN 확인은 받지 않는다. 폰을 잃고 PIN도 잊은 사람이 다시 들어오는 경로이고, 공격자는 새 PIN을 맡기면 되므로
 *       막는 효과도 없다. 대신 1원 하루 3회·하루 10번 실패 잠금으로 찍어 맞히기를 막는다</li>
 *   <li>끝나면 그 사용자의 가입 진행 토큰을 모두 지운다. 가입 완료 응답을 잃고 다시 부르면 401 SIGNUP_TOKEN_INVALID → 앱은 로그인으로 간다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DeviceRegistrationService {

    static final int MAX_DEVICES = 1;

    private final AppUserRepository userRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserDeviceEventRepository eventRepository;
    private final SignupTokenStore signupTokenStore;
    private final LinkedAccountRepository accountRepository;
    private final SignupProgress signupProgress;
    private final AccountLinkService accountLinkService;
    private final StepUpService stepUpService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenStore refreshTokenStore;
    private final TransactionTemplate transaction;
    private final Clock clock;

    DeviceRegisterResponse register(SetupActor actor, DeviceRegisterRequest request, String stepUpToken) {
        NewKeys keys = NewKeys.of(request);
        if (actor.isMember()) {
            return replaceOwnKeys(actor.me(), keys, stepUpToken);
        }
        Long userId = actor.userId();
        VerifiedAccount recovered = actor.isDeviceRecovery() ? claimOneWon(userId, request) : null;
        Optional<String> stagedPin = actor.isDeviceRecovery()
                ? signupTokenStore.stagedPinHash(actor.signupToken()) : Optional.empty();
        LocalDateTime now = now();
        List<Long> revokedByRecovery = new ArrayList<>();

        UserDevice device = transaction.execute(status -> {
            AppUser user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID));
            if (actor.isDeviceRecovery()) {
                if (!user.isActive()) {
                    throw new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID);
                }
                // 1원을 보낸 뒤 그 계좌 연결을 끊었으면 그 계좌로는 본인을 확인할 수 없다.
                accountRepository.findByUserIdAndBankCodeAndAccountNoHash(userId, recovered.bankCode(),
                                recovered.accountNoHash())
                        .filter(LinkedAccount::isConnected)
                        .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED));
                stagedPin.ifPresent(pinHash -> user.changePin(pinHash, now));
                revokedByRecovery.addAll(revokeOtherDevices(userId, keys.deviceUuid()));
            } else {
                if (user.getStatus() != AppUserStatus.PENDING) {
                    throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
                }
                signupProgress.requireReadyForDevice(actor, user);
            }
            UserDevice saved = save(user, keys);
            if (!user.isActive()) {
                user.activate();
            }
            return saved;
        });

        // 커밋 뒤에 끊는다. 롤백되면 해제도 없던 일이므로 로그인도 그대로 둔다.
        revokedByRecovery.forEach(refreshTokenStore::revoke);
        signupTokenStore.deleteAllForUser(userId);
        IssuedAccessToken access = accessTokenIssuer.issue(userId, device.getId());
        String refresh = refreshTokenStore.issue(device.getId());
        return new DeviceRegisterResponse(device.getId(), device.getDeviceName(), device.isBiometricEnabled(),
                device.getStatus(), now, access.token(), refresh, access.expiresIn());
    }

    /**
     * 로그인한 이 기기의 키를 바꾼다(생체 켜기·끄기). 다른 deviceId는 400. 새 키로 로그인할 수 있게 되므로 본인 인증을 받는다.
     * 기기 확인을 먼저 해서, 잘못된 요청으로 본인 인증 토큰을 버리지 않게 한다.
     */
    private DeviceRegisterResponse replaceOwnKeys(AuthUser me, NewKeys keys, String stepUpToken) {
        UserDevice current = deviceRepository.findById(me.userDeviceId())
                .filter(d -> !d.isRevoked())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_NOT_REGISTERED));
        if (!current.getDeviceUuid().equals(keys.deviceUuid())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.of(
                    new FieldErrorResponse("deviceId", "로그인한 이 기기의 deviceId만 바꿀 수 있습니다."))));
        }
        stepUpService.requireStepUp(me, StepUpPurpose.SECURITY_SETTINGS, stepUpToken);
        LocalDateTime now = now();
        UserDevice device = transaction.execute(status -> {
            AppUser user = userRepository.findByIdForUpdate(me.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_NOT_REGISTERED));
            return save(user, keys);
        });
        return new DeviceRegisterResponse(device.getId(), device.getDeviceName(), device.isBiometricEnabled(),
                device.getStatus(), now, null, null, null);
    }

    /**
     * 기기 재등록은 연결해 둔 계좌의 1원 인증으로 본인을 확인한다. 틀리면 400 AUTH_CODE_MISMATCH(계좌 연결과 같은 처리).
     * 확인 결과는 꺼내면서 지워 한 번만 쓴다. 같은 토큰으로 동시에 와도 기기는 하나만 등록된다.
     */
    private VerifiedAccount claimOneWon(Long userId, DeviceRegisterRequest request) {
        if (isBlank(request.verificationToken()) || isBlank(request.authCode())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.of(
                    new FieldErrorResponse("authCode", "기기 재등록은 1원 인증 토큰과 인증코드가 필요합니다."))));
        }
        return accountLinkService.claimForRecovery(userId, request.verificationToken(), request.authCode());
    }

    /**
     * 기기 재등록에서 이 폰(deviceUuid) 말고 해제되지 않은 기기를 모두 해제한다. 사용자 행을 잠근 트랜잭션 안에서 부른다.
     * 기기 행도 잠근다. 잠그지 않으면 그 사이 옛 기기 로그인이 커밋해 버전이 바뀌어 409가 나고, 이미 써 버린 1원 인증부터
     * 다시 해야 한다(잠금 순서는 다른 곳과 같은 사용자 → 기기).
     *
     * @return 해제한 기기 번호들. 커밋 뒤 Refresh Token을 지울 때 쓴다
     */
    private List<Long> revokeOtherDevices(Long userId, String deviceUuid) {
        List<Long> revoked = new ArrayList<>();
        for (UserDevice other : deviceRepository.findByUserIdAndStatusNotForUpdate(userId, DeviceStatus.REVOKED)) {
            if (other.getDeviceUuid().equals(deviceUuid)) {
                continue;
            }
            other.revoke();
            eventRepository.save(new UserDeviceEvent(other.getId(), DeviceEventType.REVOKED, "기기 재등록으로 해제"));
            revoked.add(other.getId());
        }
        deviceRepository.flush();
        return revoked;
    }

    /** 사용자 행을 잠근 트랜잭션 안에서 부른다. 같은 폰이면 키 교체, 처음이면 새로 저장하고 이력을 남긴다. */
    private UserDevice save(AppUser user, NewKeys keys) {
        Optional<UserDevice> existing = deviceRepository.findByUserIdAndDeviceUuid(user.getId(), keys.deviceUuid());
        requireDeviceSlot(user.getId(), existing);
        UserDevice device;
        DeviceEventType event;
        if (existing.isPresent()) {
            device = existing.get();
            event = device.isRevoked() ? DeviceEventType.REGISTERED : DeviceEventType.KEY_ROTATED;
            device.replaceKeys(keys.deviceName(), keys.platform(), keys.devicePublicKey(),
                    DeviceSignatureVerifier.EC_P256, keys.biometricPublicKey());
            deviceRepository.flush();
        } else {
            device = deviceRepository.saveAndFlush(UserDevice.register(user, keys.deviceUuid(), keys.deviceName(),
                    keys.platform(), keys.devicePublicKey(), DeviceSignatureVerifier.EC_P256, keys.biometricPublicKey()));
            event = DeviceEventType.REGISTERED;
        }
        eventRepository.save(new UserDeviceEvent(device.getId(), event,
                device.isBiometricEnabled() ? "기기 키 + 생체 키" : "기기 키"));
        return device;
    }

    /** 이 폰을 빼고 해제되지 않은 기기가 이미 있으면 409 DEVICE_LIMIT_EXCEEDED(안전장치). 같은 폰의 키 교체·되살리기는 대수가 늘지 않는다. */
    private void requireDeviceSlot(Long userId, Optional<UserDevice> existing) {
        long others = deviceRepository.countByUserIdAndStatusNot(userId, DeviceStatus.REVOKED)
                - (existing.filter(d -> !d.isRevoked()).isPresent() ? 1 : 0);
        if (others >= MAX_DEVICES) {
            throw new BusinessException(ErrorCode.DEVICE_LIMIT_EXCEEDED);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 요청에서 검사를 마친 키·기기 정보. deviceId는 소문자로 맞춘다(DB 칸이 대소문자를 구분). */
    private record NewKeys(String deviceUuid, String deviceName, DevicePlatform platform, String devicePublicKey,
            String biometricPublicKey) {

        static NewKeys of(DeviceRegisterRequest request) {
            DevicePublicKeys.requireP256(request.devicePublicKey());
            String biometricKey = isBlank(request.biometricPublicKey()) ? null : request.biometricPublicKey();
            if (biometricKey != null) {
                DevicePublicKeys.requireP256(biometricKey);
                // 생체 키는 생체 인증을 거쳐야 쓸 수 있는 별도 키다. 기기 키와 같으면 생체 없이도 생체 로그인이 된다.
                if (biometricKey.strip().equals(request.devicePublicKey().strip())) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.of(
                            new FieldErrorResponse("biometricPublicKey", "생체 키는 기기 키와 다른 키여야 합니다."))));
                }
                if (request.biometricTypes() == null || request.biometricTypes().isEmpty()) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.of(
                            new FieldErrorResponse("biometricTypes", "생체 키를 보내면 생체 종류(FINGERPRINT·FACE)가 필요합니다."))));
                }
            }
            String name = isBlank(request.deviceName()) ? null : request.deviceName().strip();
            return new NewKeys(request.deviceId().toLowerCase(Locale.ROOT), name, request.platform(),
                    request.devicePublicKey(), biometricKey);
        }
    }
}
