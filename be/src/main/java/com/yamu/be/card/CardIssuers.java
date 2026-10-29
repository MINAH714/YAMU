package com.yamu.be.card;

import java.util.Map;

/**
 * 카드사 코드(card_product.issuer_code) → 앱에 보여 줄 이름·대표색. 카드사 테이블이 없어 코드로 둔다.
 * 대표색은 화면 표시용 임시값이다. 모르는 코드는 이름을 코드 그대로, 색은 null로 준다.
 */
final class CardIssuers {

    record Issuer(String code, String name, String color) {
    }

    private static final Map<String, Issuer> KNOWN = Map.of(
            "SHINHAN", new Issuer("SHINHAN", "신한카드", "#0046FF"),
            "KB", new Issuer("KB", "KB국민카드", "#FFBC00"),
            "WOORI", new Issuer("WOORI", "우리카드", "#0067AC"),
            "HANA", new Issuer("HANA", "하나카드", "#009490"),
            "HYUNDAI", new Issuer("HYUNDAI", "현대카드", "#000000"),
            "SAMSUNG", new Issuer("SAMSUNG", "삼성카드", "#1428A0"),
            "LOTTE", new Issuer("LOTTE", "롯데카드", "#ED1C24"),
            "BC", new Issuer("BC", "BC카드", "#F04F4F"),
            "NH", new Issuer("NH", "NH농협카드", "#00A651"));

    private CardIssuers() {
    }

    static Issuer of(String code) {
        return KNOWN.getOrDefault(code, new Issuer(code, code, null));
    }
}
