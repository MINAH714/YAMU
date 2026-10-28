package com.yamu.be.auth.domain;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * step_up_audit_log. 로그인·본인 인증의 성공·실패 기록. 한 번 쓰고 바꾸지 않는다.
 * 기기·사용자는 복합 FK(user_device_id, user_id)라 연관관계 대신 번호로 들고 있다.
 */
@Entity
@Table(name = "step_up_audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StepUpAuditLog extends BaseCreatedTimeEntity {

    static final int USER_AGENT_MAX = 512;
    static final int IP_MAX = 45;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_up_audit_log_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_device_id", nullable = false)
    private Long userDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuthMethod authMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepUpPurpose purpose;

    @Column(nullable = false, length = 16)
    private String result;

    /** 원문 없이 남기는 실패 코드(예: PIN_MISMATCH). 성공이면 null(ck_audit_failure). */
    @Column(length = 100)
    private String failReason;

    @Column(length = 128)
    private String challengeId;

    @Column(length = IP_MAX)
    private String ipAddress;

    @Column(length = USER_AGENT_MAX)
    private String userAgent;

    private StepUpAuditLog(Long userId, Long userDeviceId, AuthMethod authMethod, StepUpPurpose purpose,
            String failReason, String challengeId, String ipAddress, String userAgent) {
        this.userId = userId;
        this.userDeviceId = userDeviceId;
        this.authMethod = authMethod;
        this.purpose = purpose;
        this.result = failReason == null ? "SUCCESS" : "FAILURE";
        this.failReason = failReason;
        this.challengeId = challengeId;
        this.ipAddress = truncate(ipAddress, IP_MAX);
        this.userAgent = truncate(userAgent, USER_AGENT_MAX);
    }

    public static StepUpAuditLog success(Long userId, Long userDeviceId, AuthMethod method, StepUpPurpose purpose,
            String challengeId, String ipAddress, String userAgent) {
        return new StepUpAuditLog(userId, userDeviceId, method, purpose, null, challengeId, ipAddress, userAgent);
    }

    public static StepUpAuditLog failure(Long userId, Long userDeviceId, AuthMethod method, StepUpPurpose purpose,
            String failReason, String challengeId, String ipAddress, String userAgent) {
        return new StepUpAuditLog(userId, userDeviceId, method, purpose, failReason, challengeId, ipAddress, userAgent);
    }

    public boolean isSuccess() {
        return failReason == null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
