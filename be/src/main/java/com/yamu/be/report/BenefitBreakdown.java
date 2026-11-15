package com.yamu.be.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 요청 지역 상태만 사용하는 집계. 카드·혜택 한도와 청구 금액은 변경하지 않는다. */
final class BenefitBreakdown {
    private static final List<String> TYPES = List.of("DISCOUNT", "CASHBACK", "POINT");

    record Result(long expectedBenefit, long confirmedBenefit, List<BenefitReportResponse.TypeAmount> byType,
            List<BenefitReportResponse.CardBenefit> cards, long unconvertedBenefitCount) {}

    static Result aggregate(List<ReportReader.BenefitLine> lines) {
        Map<String, Amount> totals = amounts();
        Map<Long, Card> cards = new LinkedHashMap<>();
        for (var line : lines) {
            Card card = cards.computeIfAbsent(line.userCardId(), id -> new Card(line));
            if (line.type() == null) continue; // 혜택 줄이 없는 승인 거래
            if (!totals.containsKey(line.type())) throw new IllegalStateException("지원하지 않는 혜택 종류");
            totals.get(line.type()).add(line);
            card.amounts.get(line.type()).add(line);
        }
        List<BenefitReportResponse.TypeAmount> byType = response(totals);
        return new Result(sum(byType, BenefitReportResponse.TypeAmount::expectedBenefit),
                sum(byType, BenefitReportResponse.TypeAmount::confirmedBenefit), byType, cards.values().stream().map(Card::response)
                .sorted(Comparator.comparingLong(BenefitReportResponse.CardBenefit::expectedBenefit).reversed()
                        .thenComparing(BenefitReportResponse.CardBenefit::userCardId, Comparator.nullsLast(Long::compareTo)))
                .toList(), sum(byType, BenefitReportResponse.TypeAmount::unconvertedBenefitCount));
    }

    private static Map<String, Amount> amounts() {
        var result = new LinkedHashMap<String, Amount>();
        TYPES.forEach(type -> result.put(type, new Amount()));
        return result;
    }

    private static List<BenefitReportResponse.TypeAmount> response(Map<String, Amount> amounts) {
        var result = new ArrayList<BenefitReportResponse.TypeAmount>();
        amounts.forEach((type, a) -> result.add(new BenefitReportResponse.TypeAmount(type, a.total, a.confirmed,
                Math.subtractExact(a.total, a.confirmed), a.unconverted)));
        return List.copyOf(result);
    }

    private static long sum(List<BenefitReportResponse.TypeAmount> values,
            java.util.function.ToLongFunction<BenefitReportResponse.TypeAmount> field) {
        return values.stream().mapToLong(field).reduce(0L, Math::addExact);
    }

    private static final class Amount {
        long total;
        long confirmed;
        long unconverted;
        void add(ReportReader.BenefitLine line) {
            if (line.krwAmount() != null) {
                total = Math.addExact(total, line.krwAmount());
                if (line.confirmed()) confirmed = Math.addExact(confirmed, line.krwAmount());
            } else if (line.benefitValue() != null && line.benefitValue().signum() > 0) {
                unconverted = Math.addExact(unconverted, 1);
            }
        }
    }

    private static final class Card {
        final ReportReader.BenefitLine identity;
        final Map<String, Amount> amounts = amounts();
        Card(ReportReader.BenefitLine identity) { this.identity = identity; }
        BenefitReportResponse.CardBenefit response() {
            var byType = BenefitBreakdown.response(amounts);
            long total = sum(byType, BenefitReportResponse.TypeAmount::expectedBenefit);
            long confirmed = sum(byType, BenefitReportResponse.TypeAmount::confirmedBenefit);
            return new BenefitReportResponse.CardBenefit(identity.userCardId(), identity.cardProductId(),
                    identity.cardName(), identity.cardAlias(), total, confirmed, Math.subtractExact(total, confirmed),
                    sum(byType, BenefitReportResponse.TypeAmount::unconvertedBenefitCount), byType);
        }
    }
}
