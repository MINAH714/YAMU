package com.yamu.be.account.domain;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * linked_account. 사용자가 연결한 SSAFY 금융망 계좌의 참조. 잔액·거래 원본은 금융망에 있다.
 *
 * <p>계좌번호는 암호문(account_no_enc)과 조회 해시(account_no_hash)로만 둔다. 화면에는 끝 4자리만 쓴다.
 * 사용자는 연관관계 대신 user_id로 둔다. 소유 확인은 번호 비교만 하면 되고, 사용자 엔티티를 읽을 일이 없다.
 */
@Entity
@Table(name = "linked_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkedAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "linked_account_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String bankCode;

    @Column(nullable = false, length = 2048)
    private byte[] accountNoEnc;

    @Column(nullable = false, columnDefinition = "binary(32)")
    private byte[] accountNoHash;

    @Column(length = 100)
    private String accountAlias;

    @Column(columnDefinition = "char(4)")
    private String accountLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LinkedAccountStatus status;

    /** 연결할 때 쓴 본인 인증(PIN·생체) 기록. 가입 중에는 본인 인증이 없어 null(휴대폰 본인인증·1원 인증이 대신함). */
    private Long stepUpAuditLogId;

    @Column(nullable = false)
    private LocalDateTime connectedAt;

    private LocalDateTime disconnectedAt;

    @Version
    private Long version;

    private LinkedAccount(Long userId, String bankCode, byte[] accountNoEnc, byte[] accountNoHash, String accountLast4) {
        this.userId = userId;
        this.bankCode = bankCode;
        this.accountNoEnc = accountNoEnc;
        this.accountNoHash = accountNoHash;
        this.accountLast4 = accountLast4;
    }

    /** 1원 인증을 마친 계좌를 새로 연결한다. accountNoEnc·accountNoHash는 global.crypto로 만든 값이다. */
    public static LinkedAccount connect(Long userId, String bankCode, byte[] accountNoEnc, byte[] accountNoHash,
            String accountLast4, String alias, Long stepUpAuditLogId, LocalDateTime now) {
        LinkedAccount account = new LinkedAccount(userId, bankCode, accountNoEnc, accountNoHash, accountLast4);
        account.reconnect(alias, stepUpAuditLogId, now);
        return account;
    }

    /** 끊었던 계좌를 다시 연결한다. 같은 계좌는 행을 새로 만들지 않는다(uk_account_identity). */
    public void reconnect(String alias, Long stepUpAuditLogId, LocalDateTime now) {
        this.status = LinkedAccountStatus.CONNECTED;
        this.accountAlias = alias;
        this.stepUpAuditLogId = stepUpAuditLogId;
        this.connectedAt = now;
        this.disconnectedAt = null;
    }

    public boolean isConnected() {
        return status == LinkedAccountStatus.CONNECTED;
    }
}
