package com.yamu.be.account;

import com.yamu.be.auth.SignupTokenStore;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import com.yamu.be.user.domain.AppUserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Access Token과 가입 진행 토큰 중 무엇으로 왔는지 보고 {@link AccountActor}를 만든다.
 *
 * <p>가입 진행 토큰("sgt_…")은 JWT가 아니라 SecurityConfig가 JWT 검사에서 빼 준다. 그래서 여기서 직접 Redis를 확인한다.
 *
 * <p>가입 진행 토큰은 30분 동안 살아 있어서, 그 사이 사용자 상태가 바뀌었을 수 있다. 모드와 지금 상태가 맞을 때만 받는다.
 * <ul>
 *   <li>SIGNUP: 가입 중(PENDING)일 때만. 가입을 마친(ACTIVE) 뒤 남은 토큰으로 본인 인증 없이 계좌를 연결하지 못하게</li>
 *   <li>DEVICE_RECOVERY: 가입을 마친(ACTIVE) 사용자만. 기기 재등록은 이미 가입한 사람이 새 폰을 등록하는 흐름이다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccountActorResolver {

    private static final String BEARER = "Bearer ";

    private final SignupTokenStore signupTokenStore;
    private final AppUserRepository userRepository;

    /**
     * @param me            Access Token이 맞으면 Spring Security가 넣어 준 사용자, 아니면 null
     * @param authorization Authorization 헤더 원문
     */
    public AccountActor resolve(AuthUser me, String authorization) {
        if (me != null) {
            return AccountActor.member(me);
        }
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).strip() : null;
        if (token == null || token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        SignupSession session = signupTokenStore.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID));
        if (session.userId() == null) {
            // 가입 진행 토큰은 시드 사용자에 묶여 발급된다. 사용자가 없는 토큰이면 금융망(userKey)을 부를 수 없다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        AppUserStatus status = userRepository.findById(session.userId()).map(AppUser::getStatus).orElse(null);
        AppUserStatus expected = session.mode() == SignupMode.DEVICE_RECOVERY ? AppUserStatus.ACTIVE : AppUserStatus.PENDING;
        if (status != expected) {
            throw new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID);
        }
        return AccountActor.onboarding(session.userId(), session.mode());
    }
}
