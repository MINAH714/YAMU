package com.yamu.be.auth;

import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.auth.domain.SignupStep;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가입 순서(계좌 연결 → PIN 설정 → 기기 등록)를 건너뛰지 못하게 막는다.
 * 앞 단계를 안 했으면 409 SIGNUP_STEP_INCOMPLETE, details.nextStep에 해야 할 단계를 준다(명세 「기기 등록」).
 *
 * <p>진행 단계는 따로 저장하지 않고 DB(계좌·PIN)를 보고 판단한다. 다만 "계좌 연결"은 DB에 연결 계좌가 있는 것만으로는 부족하고,
 * <b>지금 가입 진행 토큰으로 1원 인증을 마쳤어야</b> 한다. 계좌를 연결해 두고 멈춘 가입 중 사용자를 다른 사람이
 * 본인인증(연출)만으로 이어받아 PIN을 바꾸고 기기를 등록하지 못하게 하려고. 새 토큰이면 연결된 계좌로 1원 인증을 다시 한다.
 */
@Component
@RequiredArgsConstructor
class SignupProgress {

    private final LinkedAccountRepository accountRepository;

    /** PIN 설정 전: 이 가입 세션에서 1원 인증을 마쳤고 연결 계좌가 있는지. */
    void requireAccountLinked(SetupActor actor) {
        if (!actor.session().oneWonVerified()
                || !accountRepository.existsByUserIdAndStatus(actor.userId(), LinkedAccountStatus.CONNECTED)) {
            throw incomplete(SignupStep.ACCOUNT_LINK);
        }
    }

    /** 기기 등록 전: 계좌 연결(이 세션의 1원 인증 포함)과 PIN 설정을 마쳤는지. */
    void requireReadyForDevice(SetupActor actor, AppUser user) {
        requireAccountLinked(actor);
        if (user.getPinHash() == null) {
            throw incomplete(SignupStep.PIN_SETUP);
        }
    }

    static BusinessException incomplete(SignupStep nextStep) {
        return new BusinessException(ErrorCode.SIGNUP_STEP_INCOMPLETE, Map.of("nextStep", nextStep.name()));
    }
}
