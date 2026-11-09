package com.yamu.be.card.domain;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * card_product. 카드 상품. 상품·혜택 데이터는 시드로 들어가고 BE 1은 읽기만 한다.
 *
 * <p>카드 확인·등록과 다른 기능에 넘기는 카드 정보에 필요한 칼럼만 매핑한다(약관 주소 등은 뺐다).
 */
@Entity
@Table(name = "card_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardProduct extends BaseTimeEntity {

    @Id
    @Column(name = "card_product_id")
    private Long id;

    /** YAMU 카드사 코드(예: SHINHAN). 표시 이름은 {@code CardIssuers} */
    @Column(nullable = false, length = 32)
    private String issuerCode;

    @Column(nullable = false, length = 150)
    private String productName;

    /** 비공개 S3의 카드 이미지 객체 키. 없으면 기본 카드 그림을 사용한다. */
    @Column(length = 1024)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CardType cardType;

    /** 이용기간 마감일 = 결제일 − N일의 N. 신용카드에만 있다(POL-005). 체크카드·미설정은 null */
    @Column(columnDefinition = "smallint")
    private Integer billingCutoffDays;
}
