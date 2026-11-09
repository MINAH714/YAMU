package com.yamu.be.account;

import com.yamu.be.auth.SignupSessionResolver;
import com.yamu.be.auth.SignupSessionResolver.OnboardingToken;
import com.yamu.be.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Access Token과 가입 진행 토큰 중 무엇으로 왔는지 보고 {@link AccountActor}를 만든다.
 *
 * <p>가입 진행 토큰("sgt_…")은 JWT가 아니라 SecurityConfig가 JWT 검사에서 빼 준다. 모드와 사용자 상태 확인은
 * PIN 설정·기기 등록과 같은 규칙({@link SignupSessionResolver})을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AccountActorResolver {

    private final SignupSessionResolver signupSessionResolver;

    /**
     * @param me            Access Token이 맞으면 Spring Security가 넣어 준 사용자, 아니면 null
     * @param authorization Authorization 헤더 원문
     */
    public AccountActor resolve(AuthUser me, String authorization) {
        if (me != null) {
            return AccountActor.member(me);
        }
        OnboardingToken onboarding = signupSessionResolver.resolve(authorization);
        return AccountActor.onboarding(onboarding.session().userId(), onboarding.session().mode(), onboarding.token());
    }
}
