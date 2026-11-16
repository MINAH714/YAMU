package com.yamu.be.payment.barcode;

import com.yamu.be.auth.domain.AuthMethod;
import java.time.Instant;

/**
 * Redis에 보관하는 바코드 한 건. 시각은 모두 UTC다.
 *
 * @param paymentId  POS가 스캔해 만든 결제 요청 번호. 스캔 전(ISSUED·EXPIRED)에는 null
 * @param auth       이 코드를 연 본인 인증. 재발급(S15P31E103-530)이 다시 인증하지 않아도 되는지 판단한다.
 *                   이 칸이 생기기 전에 발급된 코드는 null이라 재발급할 수 없다
 * @param reissuedTo 재발급으로 이 코드를 대신한 새 토큰. 같은 코드로 재발급이 다시 오면(연타·재시도) 이 코드를 돌려준다
 */
public record Barcode(String token, Long userId, BarcodeStatus status, Instant issuedAt, Instant expiresAt,
        Long paymentId, IssueAuth auth, String reissuedTo) {

    public Barcode(String token, Long userId, BarcodeStatus status, Instant issuedAt, Instant expiresAt) {
        this(token, userId, status, issuedAt, expiresAt, null);
    }

    public Barcode(String token, Long userId, BarcodeStatus status, Instant issuedAt, Instant expiresAt,
            Long paymentId) {
        this(token, userId, status, issuedAt, expiresAt, paymentId, null, null);
    }

    /**
     * 지금 시각 기준 상태. 저장된 상태가 ISSUED여도 만료 시각이 지났으면 EXPIRED다.
     * 만료 시각 정각부터 만료로 본다. SCANNED 이후의 만료는 결제 마감(60초)이 정하므로 여기서 바꾸지 않는다.
     */
    public BarcodeStatus statusAt(Instant now) {
        if (status == BarcodeStatus.ISSUED && !now.isBefore(expiresAt)) {
            return BarcodeStatus.EXPIRED;
        }
        return status;
    }

    /**
     * 코드를 처음 연 본인 인증. 재발급한 코드는 이전 코드의 값을 그대로 물려받아, 재발급을 이어 붙여도 인증 시각이 늦춰지지 않는다.
     *
     * @param userDeviceId 인증한 기기
     * @param authAt       본인 인증 토큰으로 코드를 처음 발급한 시각
     * @param method       인증 방식. 생체로 연 뒤 "간편 비밀번호만"으로 바꿨으면 재발급하지 않는다
     * @param auditLogId   그 인증의 step_up_audit_log 번호. 재발급은 새 인증이 아니라 기록을 따로 남기지 않고 이 번호로 따라간다
     */
    public record IssueAuth(Long userDeviceId, Instant authAt, AuthMethod method, Long auditLogId) {
    }
}
