package com.yamu.be.auth;

import com.yamu.be.auth.LoginAttemptProcessor.LoginCommand;
import com.yamu.be.auth.LoginAttemptProcessor.LoginOutcome;
import com.yamu.be.auth.LoginChallengeStore.IssuedChallenge;
import com.yamu.be.auth.LoginChallengeStore.StoredChallenge;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.auth.dto.LoginChallengeResponse;
import com.yamu.be.auth.dto.LoginResponse;
import com.yamu.be.auth.dto.TokenResponse;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AccessTokenIssuer;
import com.yamu.be.global.security.AccessTokenIssuer.IssuedAccessToken;
import com.yamu.be.global.security.AuthUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 로그인 준비·로그인·토큰 재발급·로그아웃 (DEC-001: 등록 기기 + PIN·생체).
 *
 * <pre>
 * 1. challenge 발급(Redis 60초) → 2. 앱이 기기 키(PIN) 또는 생체 키로 서명 → 3. 서명·PIN 검증 → Access(JWT 30분) + Refresh(14일)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserDeviceRepository deviceRepository;
    private final LoginChallengeStore challengeStore;
    private final LoginAttemptProcessor attemptProcessor;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenIssuer accessTokenIssuer;
    private final OnboardingStatusReader onboardingStatusReader;
    private final LoginRateLimiter rateLimiter;
    private final Clock clock;

    /**
     * 기기 상태만 보고 challenge를 준다. 기기 번호만 알아서는 서명을 만들 수 없지만, 기기 번호를 하나씩 넣어 보며
     * 기기 상태를 알아내는 것은 호출 횟수 제한으로 막는다.
     */
    public LoginChallengeResponse issueChallenge(Long userDeviceId, ClientInfo client) {
        rateLimiter.check(userDeviceId, client.ipAddress());
        UserDevice device = usableDevice(userDeviceId);
        if (device.isLockedAt(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.DEVICE_LOCKED, DeviceLockDetails.of(device));
        }
        IssuedChallenge challenge = challengeStore.issue(device.getId());
        return new LoginChallengeResponse(challenge.challengeId(), challenge.challenge(), challenge.expiresIn(),
                device.isBiometricEnabled());
    }

    public LoginResponse login(LoginCommand command, ClientInfo client) {
        rateLimiter.check(command.userDeviceId(), client.ipAddress());
        if (command.method() == AuthMethod.PIN && !PinHasher.isPinFormat(command.pin())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    Map.of("fieldErrors", List.of(new FieldErrorResponse("pin", "PIN 방식은 간편 비밀번호 숫자 6자리가 필요합니다."))));
        }
        // challenge는 한 번만 쓸 수 있다. 다른 기기용 challenge를 가져와 쓰는 것도 막는다.
        StoredChallenge challenge = challengeStore.consume(command.challengeId())
                .filter(c -> c.userDeviceId().equals(command.userDeviceId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_EXPIRED));

        LoginOutcome outcome = attemptProcessor.verify(command, challenge, client);
        if (outcome.failed()) {
            throw new BusinessException(outcome.error(), outcome.details());
        }
        IssuedAccessToken access = accessTokenIssuer.issue(outcome.userId(), outcome.userDeviceId());
        String refresh = refreshTokenStore.issue(outcome.userDeviceId());
        return new LoginResponse(access.token(), refresh, TOKEN_TYPE, access.expiresIn(),
                new LoginResponse.LoginUser(outcome.userId(), outcome.name(),
                        onboardingStatusReader.isCompleted(outcome.userId())));
    }

    /**
     * Refresh Token을 새것으로 바꾸고 Access Token을 다시 발급한다.
     * 기기가 해제됐거나 사용자가 활성 상태가 아니면 세션을 지운다. 잠긴 기기는 재발급하지 않는다(명세).
     */
    public TokenResponse refresh(String refreshToken) {
        OptionalLong deviceId = RefreshTokenStore.deviceIdOf(refreshToken);
        if (deviceId.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        UserDevice device = deviceRepository.findWithUserById(deviceId.getAsLong()).orElse(null);
        if (device == null || !device.isUsable()) {
            refreshTokenStore.revoke(deviceId.getAsLong());
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (device.isLockedAt(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        String rotated = refreshTokenStore.rotate(refreshToken);
        if (rotated == null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        IssuedAccessToken access = accessTokenIssuer.issue(device.getUser().getId(), device.getId());
        return new TokenResponse(access.token(), rotated, TOKEN_TYPE, access.expiresIn());
    }

    /**
     * 이 기기의 세션(Refresh Token)을 지운다. Access Token이 기기를 증명하므로 본문 토큰과 상관없이 요청한 기기의 세션을 지운다.
     * 이미 발급된 Access Token은 만료(최대 30분)까지 쓸 수 있다.
     */
    public void logout(AuthUser me) {
        refreshTokenStore.revoke(me.userDeviceId());
    }

    private UserDevice usableDevice(Long userDeviceId) {
        return deviceRepository.findWithUserById(userDeviceId)
                .filter(UserDevice::isUsable)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_NOT_REGISTERED));
    }
}
