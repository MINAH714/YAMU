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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user_card. 사용자가 보유한 카드.
 *
 * <p>사용자 명의 카드는 시드로 미리 PENDING(등록 전)으로 들어가 있고, 사용자가 카드 확인·등록을 마치면 ACTIVE가 된다.
 * 카드번호는 암호문(card_no_enc)과 조회 해시(card_no_hash)로만 두고 원문은 저장하지 않는다(NFR-003).
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

    /**
     * 카드사(시드)가 정한 출금·결제 계좌. 시드 때는 linked_account 행이 없어 계좌 자체를 적어 두고,
     * 등록 때 사용자가 연결한 계좌와 은행 코드·조회 해시로 대조해 linkedAccountId를 채운다. 세 칸은 함께 있거나 함께 없다.
     */
    @Column(length = 20)
    private String issuerAccountBankCode;

    @Column(columnDefinition = "binary(32)")
    private byte[] issuerAccountHash;

    @Column(columnDefinition = "char(4)")
    private String issuerAccountLast4;

    @Column(nullable = false, length = 2048)
    private byte[] cardNoEnc;

    @Column(nullable = false, columnDefinition = "binary(32)")
    private byte[] cardNoHash;

    @Column(nullable = false, columnDefinition = "char(4)")
    private String cardLast4;

    @Column(length = 100)
    private String cardAlias;

    /** 신용카드 결제일. 처음 값은 카드사(시드)가 정하고 등록 뒤 카드 관리에서 바꿀 수 있다(DB는 1~31 허용). 체크카드는 null */
    @Column(columnDefinition = "smallint")
    private Integer paymentDay;

    /** DB 칼럼은 다른 테이블과 같은 규칙으로 {@code status}다. 카드사 상태와 헷갈리지 않게 코드에서는 등록 상태로 부른다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CardRegistrationStatus registrationStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardIssuerStatus issuerStatus;

    /** 처음 등록한 시각(UTC). 등록 해제 후 다시 등록해도 바꾸지 않는다. 등록 전 PENDING은 null */
    private LocalDateTime registeredAt;

    @Version
    private Long version;

    /** 카드 확인으로 찾을 수 있는 카드인지. 등록 전(PENDING)이거나 해제(UNREGISTERED)한 카드만 다시 등록할 수 있다. */
    public boolean isRegistrable() {
        return registrationStatus == CardRegistrationStatus.PENDING
                || registrationStatus == CardRegistrationStatus.UNREGISTERED;
    }

    /** 카드사가 정한 계좌가 적혀 있는지. 없으면 등록할 계좌를 정할 수 없다. */
    public boolean hasIssuerAccount() {
        return issuerAccountBankCode != null && issuerAccountHash != null;
    }

    /**
     * 보유 카드로 등록한다(ACTIVE). 결제일은 카드사(시드)가 정한 값을 그대로 쓴다.
     *
     * @param linkedAccountId 카드사가 정한 계좌와 같은, 사용자가 연결한 계좌(신용카드 결제계좌 또는 체크카드 연결 계좌)
     * @param alias           카드 별칭. 없으면 null(화면은 상품명)
     */
    public void register(Long linkedAccountId, String alias, LocalDateTime now) {
        this.linkedAccountId = linkedAccountId;
        this.cardAlias = alias;
        this.registrationStatus = CardRegistrationStatus.ACTIVE;
        if (registeredAt == null) {
            registeredAt = now;
        }
    }
}
