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
 * 가입 순서(회원가입 → 계좌 연결 → PIN 설정 → 기기 등록)를 건너뛰지 못하게 막는다.
 * 앞 단계를 안 했으면 409 SIGNUP_STEP_INCOMPLETE, details.nextStep에 해야 할 단계를 준다(명세 「기기 등록」).
 * 진행 단계는 따로 저장하지 않고 DB(사용자·계좌·PIN)를 보고 판단한다(SignupService와 같은 방식).
 */
@Component
@RequiredArgsConstructor
class SignupProgress {

    private final LinkedAccountRepository accountRepository;

    /** PIN 설정 전: 회원가입과 계좌 연결을 마쳤는지. */
    void requireAccountLinked(Long userId) {
        if (userId == null) {
            throw incomplete(SignupStep.SIGNUP);
        }
        if (!accountRepository.existsByUserIdAndStatus(userId, LinkedAccountStatus.CONNECTED)) {
            throw incomplete(SignupStep.ACCOUNT_LINK);
        }
    }

    /** 기기 등록 전: 계좌 연결과 PIN 설정을 마쳤는지. */
    void requireReadyForDevice(AppUser user) {
        requireAccountLinked(user.getId());
        if (user.getPinHash() == null) {
            throw incomplete(SignupStep.PIN_SETUP);
        }
    }

    static BusinessException incomplete(SignupStep nextStep) {
        return new BusinessException(ErrorCode.SIGNUP_STEP_INCOMPLETE, Map.of("nextStep", nextStep.name()));
    }
}
