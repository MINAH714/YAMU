package com.yamu.be.auth;

import com.yamu.be.auth.ChallengeStore.IssuedChallenge;
import com.yamu.be.auth.ChallengeStore.StoredChallenge;
import com.yamu.be.auth.DeviceCredentialProcessor.CredentialCommand;
import com.yamu.be.auth.DeviceCredentialProcessor.CredentialOutcome;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.auth.dto.StepUpChallengeResponse;
import com.yamu.be.auth.dto.StepUpVerifyRequest;
import com.yamu.be.auth.dto.StepUpVerifyResponse;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.BarcodeAuthMethod;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 본인 인증(step-up). 민감한 동작 직전에 생체 또는 간편 비밀번호로 한 번 더 확인하고, 그 동작에만 쓸 수 있는 1회용 토큰을 준다.
 *
 * <pre>
 * 1. challenge(용도와 함께, 60초) → 2. 서명·PIN 검증(로그인과 같은 처리, 실패 횟수 공유) → 3. stepUpToken(5분, 1회용)
 * 4. 대상 API: requireStepUp(me, 용도, X-Step-Up-Token) — 사용자·기기·용도가 맞을 때 한 번만 통과
 * </pre>
 *
 * 기기는 요청 본문이 아니라 Access Token의 기기(did)로 정한다. 다른 기기의 키로 인증하는 경로를 아예 만들지 않기 위해서다.
 *
 * <p>사용자가 보안 설정에서 결제 코드 인증을 "간편 비밀번호만"({@link BarcodeAuthMethod#PIN_ONLY})으로 골랐으면, BARCODE 용도는
 * 생체 인증을 받지 않는다(S15P31E103-367). challenge의 허용 방식에서 빼고, 생체로 검증을 보내면 거절하고, 설정을 바꾸기 전에
 * 생체로 받아 둔 토큰도 결제 코드 발급에서 막는다.
 */
@Service
@RequiredArgsConstructor
public class StepUpService {

    /** 대상 API가 본인 인증 토큰을 받는 헤더(명세 1장). */
    public static final String HEADER = "X-Step-Up-Token";

    private final UserDeviceRepository deviceRepository;
    private final ChallengeStore challengeStore;
    private final DeviceCredentialProcessor credentialProcessor;
    private final StepUpTokenStore tokenStore;
    private final AuthProperties properties;
    private final Clock clock;

    public StepUpChallengeResponse issueChallenge(AuthUser me, String purposeText) {
        StepUpPurpose purpose = parsePurpose(purposeText);
        UserDevice device = requireActiveDevice(me);
        LocalDateTime now = LocalDateTime.now(clock);
        IssuedChallenge challenge = challengeStore.issue(device.getId(), purpose);
        List<AuthMethod> allowed = device.isBiometricEnabled() && !pinOnly(device, purpose)
                ? List.of(AuthMethod.BIOMETRIC, AuthMethod.PIN) : List.of(AuthMethod.PIN);
        return new StepUpChallengeResponse(challenge.challengeId(), challenge.challenge(), purpose, allowed,
                expiresAt(now, challenge.expiresIn()));
    }

    public StepUpVerifyResponse verify(AuthUser me, StepUpVerifyRequest request, ClientInfo client) {
        if (request.method() == AuthMethod.PIN && !PinHasher.isPinFormat(request.pin())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    Map.of("fieldErrors", List.of(new FieldErrorResponse("pin", "PIN 방식은 간편 비밀번호 숫자 6자리가 필요합니다."))));
        }
        // 이 기기에서 본인 인증용으로 발급한 challenge만 쓸 수 있다. 로그인용 challenge는 여기서 쓸 수 없다.
        StoredChallenge challenge = challengeStore.consume(request.challengeId())
                .filter(c -> c.userDeviceId().equals(me.userDeviceId()) && c.purpose() != StepUpPurpose.LOGIN)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_EXPIRED));

        // 임시(S15P31E103-367): 명세에 따로 정한 코드가 없어 400 INVALID_REQUEST(method). 서명을 확인하기 전이라 실패 횟수는 늘지 않는다.
        if (request.method() == AuthMethod.BIOMETRIC && pinOnly(requireActiveDevice(me), challenge.purpose())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.of(
                    new FieldErrorResponse("method", "결제 코드는 간편 비밀번호로만 열도록 설정되어 있습니다."))));
        }

        CredentialCommand command = new CredentialCommand(me.userDeviceId(), request.challengeId(), request.method(),
                request.signature(), request.pin());
        CredentialOutcome outcome = credentialProcessor.verify(command, challenge, me.userId(), client);
        if (outcome.failed()) {
            throw toStepUpError(outcome);
        }
        String token = tokenStore.issue(new StepUpGrant(outcome.userId(), outcome.userDeviceId(), challenge.purpose(),
                request.method(), outcome.auditLogId()));
        return new StepUpVerifyResponse(token, challenge.purpose(), request.method(),
                expiresAt(LocalDateTime.now(clock), properties.stepUpTokenTtl().toSeconds()), outcome.auditLogId());
    }

    /**
     * 대상 API에서 부른다. 요청한 사용자·기기가 그 용도로 받은 토큰이면 한 번 통과시키고 토큰을 지운다.
     * 없거나 만료·이미 사용·다른 사용자·다른 기기·용도 불일치면 403 STEP_UP_REQUIRED.
     * 토큰을 받은 뒤 기기가 해제됐으면 DEVICE_NOT_REGISTERED, 잠겼으면 DEVICE_LOCKED(토큰은 이미 지워짐).
     *
     * <p>입력 검증을 모두 마친 뒤, 실제 변경 직전에 부른다. 토큰을 먼저 지우고 확인하므로 이 뒤에 검증 실패·롤백이 나도
     * 토큰은 돌아오지 않고(Redis는 DB 롤백과 같이 되돌아가지 않음) 사용자가 본인 인증을 다시 해야 한다.
     *
     * <pre>
     * StepUpGrant grant = stepUpService.requireStepUp(me, StepUpPurpose.BARCODE, request.getHeader(StepUpService.HEADER));
     * </pre>
     *
     * @return 인증 기록 번호(auditLogId)와 인증 방식. 계좌 연결은 auditLogId를 연결 기록에 남긴다
     */
    public StepUpGrant requireStepUp(AuthUser me, StepUpPurpose purpose, String token) {
        StepUpGrant grant = tokenStore.consume(token)
                .filter(g -> g.userId().equals(me.userId()) && g.userDeviceId().equals(me.userDeviceId())
                        && g.purpose() == purpose)
                .orElseThrow(() -> new BusinessException(ErrorCode.STEP_UP_REQUIRED));
        // 토큰은 5분까지 살아 있으므로 그사이 해제·잠긴 기기는 여기서 다시 막는다.
        UserDevice device = requireActiveDevice(me);
        // 생체로 받은 뒤 "간편 비밀번호만"으로 바꿨으면 그 토큰으로는 결제 코드를 열지 않는다(토큰은 이미 지워짐).
        if (grant.method() == AuthMethod.BIOMETRIC && pinOnly(device, purpose)) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
        }
        return grant;
    }

    /**
     * 앞서 받은 본인 인증을 다시 써도 되는지 본다(결제 코드 재발급, S15P31E103-530). 토큰은 받지 않고 {@link #requireStepUp}이 토큰을
     * 쓴 뒤 하는 확인만 다시 한다. 기기가 해제됐으면 DEVICE_NOT_REGISTERED, 잠겼으면 DEVICE_LOCKED,
     * 생체로 인증한 뒤 "간편 비밀번호만"으로 바꿨으면 REAUTH_REQUIRED.
     *
     * @param method 앞서 인증한 방식
     */
    public void requireReusable(AuthUser me, StepUpPurpose purpose, AuthMethod method) {
        UserDevice device = requireActiveDevice(me);
        if (method == AuthMethod.BIOMETRIC && pinOnly(device, purpose)) {
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
    }

    /** 요청 기기가 이 사용자의 쓸 수 있는 기기이고 지금 잠겨 있지 않은지. */
    private UserDevice requireActiveDevice(AuthUser me) {
        UserDevice device = deviceRepository.findWithUserById(me.userDeviceId())
                .filter(d -> d.isUsable() && d.getUser().getId().equals(me.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_NOT_REGISTERED));
        if (device.isLockedAt(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.DEVICE_LOCKED, DeviceLockDetails.of(device));
        }
        return device;
    }

    /** 결제 코드(BARCODE) 용도이고 사용자가 "간편 비밀번호만"을 골랐는지. 다른 용도는 설정과 상관없다. */
    private static boolean pinOnly(UserDevice device, StepUpPurpose purpose) {
        return purpose == StepUpPurpose.BARCODE
                && device.getUser().getBarcodeAuthMethod() == BarcodeAuthMethod.PIN_ONLY;
    }

    /** 로그인 기준 실패 코드를 본인 인증 명세 코드로 바꾼다. 서명·PIN 실패는 STEP_UP_FAILED(PIN이면 남은 횟수 포함). */
    private static BusinessException toStepUpError(CredentialOutcome outcome) {
        ErrorCode error = outcome.error();
        if (error == ErrorCode.SIGNATURE_INVALID || error == ErrorCode.PIN_MISMATCH) {
            return new BusinessException(ErrorCode.STEP_UP_FAILED, outcome.details());
        }
        return new BusinessException(error, outcome.details());
    }

    private static StepUpPurpose parsePurpose(String text) {
        try {
            StepUpPurpose purpose = StepUpPurpose.valueOf(text);
            if (purpose != StepUpPurpose.LOGIN) {
                return purpose;
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // 아래에서 INVALID_PURPOSE
        }
        throw new BusinessException(ErrorCode.INVALID_PURPOSE);
    }

    private static LocalDateTime expiresAt(LocalDateTime now, long seconds) {
        return now.plusSeconds(seconds).truncatedTo(ChronoUnit.SECONDS);
    }
}
