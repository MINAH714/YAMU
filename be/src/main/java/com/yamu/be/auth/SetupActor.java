package com.yamu.be.auth;

import com.yamu.be.auth.SignupTokenStore.SignupSession;
import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.global.security.AuthUser;

/**
 * 간편 비밀번호 설정·기기 등록을 부른 사람. 가입을 마친 사용자는 Access Token(me), 가입·기기 재등록 중인 사람은 가입 진행 토큰으로 온다.
 *
 * @param me           Access Token으로 왔으면 그 사용자·기기, 가입 진행 토큰이면 null
 * @param signupToken  가입 진행 토큰 원문(끝나면 지우려고 들고 있다). Access Token이면 null
 * @param session      가입 진행 토큰의 내용. Access Token이면 null
 */
record SetupActor(AuthUser me, String signupToken, SignupSession session) {

    boolean isMember() {
        return me != null;
    }

    boolean isDeviceRecovery() {
        return session != null && session.mode() == SignupMode.DEVICE_RECOVERY;
    }

    /** 가입 진행 토큰은 시드 사용자에 묶여 발급되므로 항상 있다. */
    Long userId() {
        return me != null ? me.userId() : session.userId();
    }
}
