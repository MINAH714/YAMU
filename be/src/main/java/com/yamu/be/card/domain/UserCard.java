package com.yamu.be.card.domain;

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
 * user_card. 사용자가 보유한 카드.
 *
 * <p>지금은 다른 기능의 소유 확인에 필요한 컬럼만 매핑한다. 카드번호 암호문·조회 해시·결제일은 카드 등록 기능에서 추가한다.
 * 사용자·상품·계좌는 연관관계 대신 번호로 둔다. 소유 확인과 참조 전달에는 번호만 필요하다.
 */
@Entity
@Table(name = "user_card")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCard extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_card_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "card_product_id", nullable = false)
    private Long cardProductId;

    @Column(name = "linked_account_id")
    private Long linkedAccountId;

    @Column(nullable = false, columnDefinition = "char(4)")
    private String cardLast4;

    /** DB 칼럼은 다른 테이블과 같은 규칙으로 {@code status}다. 카드사 상태와 헷갈리지 않게 코드에서는 등록 상태로 부른다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CardRegistrationStatus registrationStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardIssuerStatus issuerStatus;

    @Version
    private Long version;
}
