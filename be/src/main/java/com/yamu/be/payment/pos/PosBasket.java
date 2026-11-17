package com.yamu.be.payment.pos;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.*;

/** GS25 모의 POS 상품 코드/가격 검증. 클라이언트가 할인 대상액을 직접 지정하지 않는다. */
public final class PosBasket {
    private PosBasket() { }
    public record Line(String productCode, BigDecimal quantity) { }
    public record Item(String productCode, int quantity, long unitPrice, Boolean ibkEligible) { }
    public record Snapshot(int version, String brandCode, List<Item> items, long total, Long ibkEligibleAmount) {
        public Snapshot { items = List.copyOf(items); }
    }
    private record Product(long price, Boolean eligible) { }
    // 실제 할인 대상 범주가 확인된 상품만 true. 컵라면/떡볶이/우유는 분류 미확인으로 남긴다.
    private static final Map<String, Product> GS25 = Map.ofEntries(
            Map.entry("gs-triangle-kimbap", new Product(1100, true)),
            Map.entry("gs-egg-sandwich", new Product(2900, true)),
            Map.entry("gs-jeyuk-dosirak", new Product(5500, true)),
            Map.entry("gs-cup-ramen", new Product(1250, null)),
            Map.entry("gs-kimbap", new Product(3300, true)),
            Map.entry("gs-rose-tteokbokki", new Product(3900, null)),
            Map.entry("gs-water", new Product(1100, true)),
            Map.entry("gs-banana-milk", new Product(1700, null)),
            Map.entry("gs-cola", new Product(2100, true)),
            Map.entry("gs-chips", new Product(1700, false)),
            Map.entry("gs-cookie", new Product(1700, false)),
            Map.entry("gs-jelly", new Product(2000, false)));

    public static Snapshot validate(String brand, List<Line> lines, long amount) {
        if (lines == null) return null; // 구형 요청은 품목 미확인. 할인 대상 0원으로 바꾸지 않는다.
        if (!"GS25".equals(brand) || lines.isEmpty() || lines.size() > 50) throw invalid();
        var items = new ArrayList<Item>();
        var seen = new HashSet<String>();
        long total = 0, eligible = 0;
        boolean known = true;
        try {
            for (Line line : lines) {
                if (line == null || line.productCode() == null || line.quantity() == null
                        || !seen.add(line.productCode())) throw invalid();
                Product product = GS25.get(line.productCode());
                int quantity = line.quantity().intValueExact();
                if (product == null || quantity < 1 || quantity > 99) throw invalid();
                long subtotal = Math.multiplyExact(product.price(), quantity);
                total = Math.addExact(total, subtotal);
                if (product.eligible() == null) known = false;
                if (Boolean.TRUE.equals(product.eligible())) eligible = Math.addExact(eligible, subtotal);
                items.add(new Item(line.productCode(), quantity, product.price(), product.eligible()));
            }
        } catch (ArithmeticException failure) { throw invalid(); }
        if (total != amount) throw invalid();
        items.sort(Comparator.comparing(Item::productCode));
        return new Snapshot(1, brand, items, total, known || eligible > 0 ? eligible : null);
    }

    /** 품목 순서만 다른 재송신은 동일하게 취급한다. 금액이 같아도 품목이 다르면 다른 요청이다. */
    public static String fingerprint(List<Line> lines) {
        if (lines == null) return "";
        return lines.stream().sorted(Comparator.comparing(Line::productCode))
                .map(l -> l.productCode() + ":" + l.quantity().stripTrailingZeros().toPlainString())
                .collect(java.util.stream.Collectors.joining(",", "\nbasket:", ""));
    }
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "구매 상품·수량·합계가 POS 상품 정보와 일치하지 않습니다.");
    }
}
