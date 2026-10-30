package com.yamu.be.auth;

import com.yamu.be.auth.SignupSessionResolver.OnboardingToken;
import com.yamu.be.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Access Token과 가입 진행 토큰 중 무엇으로 왔는지 보고 {@link SetupActor}를 만든다.
 * 가입 진행 토큰의 모드·사용자 상태 확인은 계좌 연결과 같은 규칙({@link SignupSessionResolver})을 쓴다.
 */
@Component
@RequiredArgsConstructor
class SetupActorResolver {

    private final SignupSessionResolver signupSessionResolver;

    SetupActor resolve(AuthUser me, String authorization) {
        if (me != null) {
            return new SetupActor(me, null, null);
        }
        OnboardingToken onboarding = signupSessionResolver.resolve(authorization);
        return new SetupActor(null, onboarding.token(), onboarding.session());
    }
}
