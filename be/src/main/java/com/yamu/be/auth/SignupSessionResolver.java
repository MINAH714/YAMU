package com.yamu.be.auth;

import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authorization 헤더의 가입 진행 토큰("sgt_…")을 확인한다. 계좌 연결(AccountActorResolver)과
 * PIN 설정·기기 등록(SetupActorResolver)이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>가입 진행 토큰은 30분 동안 살아 있어서, 그 사이 사용자 상태가 바뀌었을 수 있다. 모드와 지금 상태가 맞을 때만 받는다.
 * <ul>
 *   <li>SIGNUP: 가입 중(PENDING)일 때만. 가입을 마친(ACTIVE) 뒤 남은 토큰으로 본인 인증 없이 계좌·PIN을 바꾸지 못하게</li>
 *   <li>DEVICE_RECOVERY: 가입을 마친(ACTIVE) 사용자만. 기기 재등록은 이미 가입한 사람이 새 폰을 등록하는 흐름이다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SignupSessionResolver {

    private static final String BEARER = "Bearer ";

    private final SignupTokenStore signupTokenStore;
    private final AppUserRepository userRepository;

    /** 헤더가 없으면 401 UNAUTHORIZED, 토큰이 없거나 만료·상태가 맞지 않으면 401 SIGNUP_TOKEN_INVALID. */
    public OnboardingToken resolve(String authorization) {
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).strip() : null;
        if (token == null || token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        SignupSession session = signupTokenStore.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID));
        AppUserStatus status = userRepository.findById(session.userId()).map(AppUser::getStatus).orElse(null);
        AppUserStatus expected = session.mode() == SignupMode.DEVICE_RECOVERY ? AppUserStatus.ACTIVE : AppUserStatus.PENDING;
        if (status != expected) {
            throw new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID);
        }
        return new OnboardingToken(token, session);
    }

    /** @param token 가입 진행 토큰 원문(1원 인증 표시·폐기에 쓴다) */
    public record OnboardingToken(String token, SignupSession session) {
    }
}
