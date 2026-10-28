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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * linked_account. 사용자가 연결한 SSAFY 금융망 계좌의 참조. 잔액·거래 원본은 금융망에 있다.
 *
 * <p>지금은 다른 기능의 소유 확인에 필요한 컬럼만 매핑한다. 계좌번호 암호문·조회 해시는 계좌 연결 기능에서 추가한다.
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

    @Column(columnDefinition = "char(4)")
    private String accountLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LinkedAccountStatus status;

    @Version
    private Long version;

    public boolean isConnected() {
        return status == LinkedAccountStatus.CONNECTED;
    }
}
