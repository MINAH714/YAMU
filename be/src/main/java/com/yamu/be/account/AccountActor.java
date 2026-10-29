package com.yamu.be.account;

import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.global.security.AuthUser;

/**
 * 계좌 연결 API를 부른 사람. 가입을 마친 사용자는 Access Token(me), 가입·기기 재등록 중인 사람은 가입 진행 토큰으로 온다.
 *
 * @param me   Access Token으로 왔으면 그 사용자·기기, 가입 진행 토큰이면 null
 * @param mode 가입 진행 토큰으로 왔을 때의 모드, Access Token이면 null
 */
public record AccountActor(Long userId, AuthUser me, SignupMode mode) {

    public static AccountActor member(AuthUser me) {
        return new AccountActor(me.userId(), me, null);
    }

    public static AccountActor onboarding(Long userId, SignupMode mode) {
        return new AccountActor(userId, null, mode);
    }

    public boolean isDeviceRecovery() {
        return mode == SignupMode.DEVICE_RECOVERY;
    }
}
