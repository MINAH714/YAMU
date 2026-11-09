package com.yamu.be.cashflow;

import com.yamu.be.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가계부 항목 등록의 Idempotency-Key 기록(S15P31E103-358). 항목을 지워도 남아, 늦게 온 재시도가 지운 항목을 다시 만들지 않는다.
 * 지운 항목의 내용을 남기지 않으려고 키·지문·항목 번호만 둔다(2026-11-09 결정).
 */
@Entity
@Table(name = "cashflow_create_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CashflowCreateRequest extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cashflow_create_request_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Idempotency-Key(UUID, 소문자). */
    @Column(name = "request_key", nullable = false, columnDefinition = "char(36)")
    private String requestKey;

    /** 처음 등록 요청 내용의 SHA-256. */
    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "binary(32)")
    private byte[] requestFingerprint;

    /** 그 요청으로 만든 규칙. 규칙을 지워도 번호는 남는다. */
    @Column(name = "cashflow_item_id", nullable = false)
    private Long cashflowItemId;

    CashflowCreateRequest(Long userId, String requestKey, byte[] requestFingerprint, Long cashflowItemId) {
        this.userId = userId;
        this.requestKey = requestKey;
        this.requestFingerprint = requestFingerprint;
        this.cashflowItemId = cashflowItemId;
    }
}
