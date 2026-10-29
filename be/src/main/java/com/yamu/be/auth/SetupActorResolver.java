package com.yamu.be.auth;

import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Access Token과 가입 진행 토큰 중 무엇으로 왔는지 보고 {@link SetupActor}를 만든다(계좌 연결의 AccountActorResolver와 같은 방식).
 * 가입 진행 토큰("sgt_…")은 JWT가 아니라 SecurityConfig가 JWT 검사에서 빼 준다. 그래서 여기서 직접 Redis를 확인한다.
 */
@Component
@RequiredArgsConstructor
class SetupActorResolver {

    private static final String BEARER = "Bearer ";

    private final SignupTokenStore signupTokenStore;

    SetupActor resolve(AuthUser me, String authorization) {
        if (me != null) {
            return new SetupActor(me, null, null);
        }
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).strip() : null;
        if (token == null || token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        SignupSession session = signupTokenStore.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNUP_TOKEN_INVALID));
        return new SetupActor(null, token, session);
    }
}
