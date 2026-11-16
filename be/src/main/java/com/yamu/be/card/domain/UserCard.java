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

    /** 카드사 사용 등록일. YAMU 최초 등록일과 구분하며 NULL은 미확인이다. */
    private java.time.LocalDate issuerRegisteredOn;
    /** 아래 전월 자격 판정이 적용되는 한국 이용월. 월이 바뀌면 재확인한다. */
    private java.time.LocalDate ibkContextMonth;
    private Boolean ibkSalaryQualified;
    private Boolean ibkMilitaryQualified;

    /** 사용자가 정한 목록 순서(작을수록 앞). 추천 동률의 마지막 기준(FIXED_ORDER). 저장 전에는 null(등록 순) */
    private Integer displayOrder;

    /** 기본 카드. 사용자당 1장(DB UNIQUE)이고 등록된 카드만 지정할 수 있다 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultCard;

    /** 마지막 결제일 변경 시각(UTC). 3개월 재변경 제한의 기준이다. 등록 때 정한 결제일은 변경이 아니라 null */
    private LocalDateTime paymentDayChangedAt;

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
     * 해제 후 다시 등록하면 새 등록이라 예전 결제일 변경 시각(3개월 제한)을 물려받지 않는다.
     *
     * @param linkedAccountId 카드사가 정한 계좌와 같은, 사용자가 연결한 계좌(신용카드 결제계좌 또는 체크카드 연결 계좌)
     * @param alias           카드 별칭. 없으면 null(화면은 상품명)
     */
    public void register(Long linkedAccountId, String alias, LocalDateTime now) {
        this.linkedAccountId = linkedAccountId;
        this.cardAlias = alias;
        this.registrationStatus = CardRegistrationStatus.ACTIVE;
        this.paymentDayChangedAt = null;
        if (registeredAt == null) {
            registeredAt = now;
        }
    }

    /** 별칭을 바꾼다. null이면 지운다(화면은 상품명). */
    public void rename(String alias) {
        this.cardAlias = alias;
    }

    /** 추천 포함(ACTIVE)·제외(INACTIVE)를 바꾼다. 등록된 카드끼리만 오간다. */
    public void changeRecommendation(CardRegistrationStatus status) {
        if (!status.isRegistered() || !registrationStatus.isRegistered()) {
            throw new IllegalStateException("등록된 카드의 추천 포함 여부만 바꿀 수 있습니다: " + registrationStatus + " → " + status);
        }
        this.registrationStatus = status;
    }

    public void markDefault(boolean defaultCard) {
        this.defaultCard = defaultCard;
    }

    /** 신용카드 결제일을 바꾸고 변경 시각을 남긴다. 3개월 제한은 호출하는 쪽이 먼저 확인한다. */
    public void changePaymentDay(int paymentDay, LocalDateTime now) {
        this.paymentDay = paymentDay;
        this.paymentDayChangedAt = now;
    }

    /** 출금 계좌(신용카드 결제계좌)를 바꾼다. 소유·연결 확인은 호출하는 쪽이 한다. */
    public void changeLinkedAccount(Long linkedAccountId) {
        this.linkedAccountId = linkedAccountId;
    }

    public void placeAt(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    /** 등록 해제. 행은 남겨 결제·실적·청구 이력의 참조를 보존하고, 기본 카드·순서 지정은 푼다. */
    public void unregister() {
        this.registrationStatus = CardRegistrationStatus.UNREGISTERED;
        this.defaultCard = false;
        this.displayOrder = null;
    }
}
