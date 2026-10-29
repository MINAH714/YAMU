package com.yamu.be.account;

import com.yamu.be.auth.SignupTokenStore;
import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Access Token과 가입 진행 토큰 중 무엇으로 왔는지 보고 {@link AccountActor}를 만든다.
 *
 * <p>가입 진행 토큰("sgt_…")은 JWT가 아니라 SecurityConfig가 JWT 검사에서 빼 준다. 그래서 여기서 직접 Redis를 확인한다.
 */
@Component
@RequiredArgsConstructor
public class AccountActorResolver {

    private static final String BEARER = "Bearer ";

    private final SignupTokenStore signupTokenStore;

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
            // 본인인증만 하고 회원가입(SSAFY 계정 생성)을 하지 않았다. userKey가 없어 금융망을 부를 수 없다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return AccountActor.onboarding(session.userId(), session.mode());
    }
}
