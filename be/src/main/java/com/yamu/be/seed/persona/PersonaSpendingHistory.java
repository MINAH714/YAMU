package com.yamu.be.seed.persona;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시연 페르소나의 카드 등록 전 모의 소비(SEED 결제)와 그달 실적을 넣는다(S15P31E103-255, 2026-11-05 결정).
 * 잔고예보 생활비는 체크카드 SEED 결제를 "첫 SEED 결제일부터 카드 등록 전날까지" 쓰므로, 가입 시연 때 카드를 등록하는 순간
 * 이 6개월이 등록 전 소비로 인정된다.
 *
 * <ul>
 *   <li>기간: (이번 달 − 6개월)의 1일부터 시드를 넣는 날 전날까지(한국 날짜). 완료 3개월 평균(AVG_3M)·AI(90일)·반복 지출
 *       규칙(직전 6개월)에 모두 충분하다.</li>
 *   <li>체크카드: 평일 커피·점심·편의점·배달, 주말 마트·영화·올리브영으로 달마다 60만~70만 원(결제를 빼고 더해 맞춤,
 *       {@link #fitMonths}). 넷플릭스 13,500원 매달 5일
 *       (3번째 달부터 반복 지출로 빠짐), 2개월 전 15일 쿠팡 129만 원 1건(100만 원 초과라 LARGE로 빠짐).</li>
 *   <li>신용카드: 매주 토요일 온라인 쇼핑, 3·17일 주유, 25일 통신비, 3개월 전 20일 90만 원 3개월 할부 1건.
 *       잔고예보 생활비에는 들어가지 않는다(신용카드는 청구로 반영).</li>
 *   <li>금액은 10원 단위로 흩뜨린다. 같은 카드·가맹점·금액이 3개 달에 나오면 반복 지출로 빠지는 규칙 때문에, 같은 가격의
 *       일상 소비가 생활비에서 빠지지 않게 하려는 것이다(규칙 개선은 후속 작업).</li>
 *   <li>같은 사람·같은 날이면 같은 값이다. 난수 대신 SSAFY 회원 ID·날짜·순번의 해시로 정한다.</li>
 *   <li>실적(card_monthly_performance)은 달마다 넣은 결제 금액의 단순 합이다. 카드사 실적 인정 규칙(제외 거래 등)은
 *       적용하지 않는다. 카드 등록 화면이 이 값을 "등록 전 실적"으로 보여 준다.</li>
 *   <li>소비 분류(spending_class)는 비워 둔다. 잔고예보 생활비 원천이 읽을 때 자동으로 채운다.</li>
 * </ul>
 */
@Component
class PersonaSpendingHistory {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    static final int MONTHS = 6;
    /** 체크카드 일상 소비(넷플릭스·큰 지출 제외) 한 달 합계의 범위. 이번 달은 지난 날수만큼 줄인다. */
    static final long MONTHLY_MIN = 600_000L;
    static final long MONTHLY_MAX = 700_000L;
    private static final List<String> CONVENIENCE = List.of("GS25", "CU", "SEVEN_ELEVEN");

    /** 시드가 쓰는 브랜드와 업종. 카드 카탈로그 시드(be/seed/01·02)에 모두 있다. 테스트도 이 목록으로 가맹점을 넣는다. */
    static final Map<String, String> BRANDS = Map.ofEntries(
            Map.entry("STARBUCKS", "COFFEE_SHOP"), Map.entry("TWOSOME_PLACE", "COFFEE_SHOP"),
            Map.entry("PAUL_BASSETT", "COFFEE_SHOP"), Map.entry("HOLLYS", "COFFEE_SHOP"),
            Map.entry("BURGER_KING", "FAST_FOOD"), Map.entry("MCDONALDS", "FAST_FOOD"), Map.entry("LOTTERIA", "FAST_FOOD"),
            Map.entry("GS25", "CONVENIENCE_STORE"), Map.entry("CU", "CONVENIENCE_STORE"),
            Map.entry("SEVEN_ELEVEN", "CONVENIENCE_STORE"),
            Map.entry("BAEMIN", "DELIVERY_APP"), Map.entry("COUPANG_EATS", "DELIVERY_APP"), Map.entry("YOGIYO", "DELIVERY_APP"),
            Map.entry("EMART", "HYPERMARKET"), Map.entry("HOMEPLUS", "HYPERMARKET"),
            Map.entry("CGV", "MOVIE_THEATER"), Map.entry("MEGABOX", "MOVIE_THEATER"),
            Map.entry("OLIVE_YOUNG", "HEALTH_BEAUTY_STORE"), Map.entry("NETFLIX", "STREAMING_SERVICE"),
            Map.entry("COUPANG", "ONLINE_MARKETPLACE"), Map.entry("SSG_COM", "ONLINE_MARKETPLACE"),
            Map.entry("KYOBO", "BOOKSTORE"), Map.entry("GS_CALTEX", "GAS_STATION"), Map.entry("SK_ENERGY", "GAS_STATION"),
            Map.entry("SKT", "TELECOMMUNICATION"));

    private final JdbcTemplate jdbc;

    PersonaSpendingHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 넣을 카드. 같은 종류가 여러 장이면 첫 장에만 넣는다. */
    record SeededCard(long userCardId, long productId, String cardType) {}

    /**
     * 카드 등록 전 모의 소비와 실적을 넣는다. 부르는 쪽(페르소나 시드)의 트랜잭션 안에서 부른다.
     *
     * @param memberId 결과를 같게 만드는 기준(SSAFY 회원 ID). 저장하지 않는다
     * @param today    한국 날짜 오늘. 이 전날까지 넣는다
     * @return 넣은 결제 수
     */
    int insert(long userId, String memberId, List<SeededCard> cards, LocalDate today, Timestamp now) {
        Map<String, Long> merchants = merchants();
        LocalDate from = YearMonth.from(today).minusMonths(MONTHS).atDay(1);
        LocalDate to = today.minusDays(1);
        List<Payment> payments = new ArrayList<>();
        cards.stream().filter(card -> "CHECK".equals(card.cardType())).findFirst()
                .ifPresent(card -> payments.addAll(check(card, memberId, from, to)));
        cards.stream().filter(card -> "CREDIT".equals(card.cardType())).findFirst()
                .ifPresent(card -> payments.addAll(credit(card, memberId, from, to)));

        List<Object[]> rows = new ArrayList<>();
        Map<Long, Map<YearMonth, Long>> performance = new LinkedHashMap<>();
        for (Payment payment : payments) {
            Long merchant = merchants.get(payment.brand());
            YearMonth month = YearMonth.from(payment.date());
            Timestamp approved = Timestamp.from(payment.date().atTime(payment.hour(), 0).atZone(KOREA).toInstant());
            String key = UUID.nameUUIDFromBytes((userId + ":" + payment.card().userCardId() + ":" + payment.date() + ":"
                    + payment.slot()).getBytes(StandardCharsets.UTF_8)).toString();
            String context = "{\"brandCode\":\"" + payment.brand() + "\",\"merchantCategoryCode\":\""
                    + BRANDS.get(payment.brand()) + "\",\"transactionKind\":\"PURCHASE\",\"source\":\"PERSONA_SEED\"}";
            rows.add(new Object[] {userId, payment.card().userCardId(), payment.card().productId(), merchant,
                    payment.online() ? "ONLINE" : "OFFLINE", context, key, sha256(key + ":" + payment.amount()),
                    payment.amount(), payment.amount(), payment.months(), payment.amount(), month.atDay(1), approved,
                    approved, now, now});
            performance.computeIfAbsent(payment.card().userCardId(), id -> new HashMap<>())
                    .merge(month, payment.amount(), Long::sum);
        }
        jdbc.batchUpdate("""
                INSERT INTO payment_transaction (user_id, user_card_id, card_product_id, merchant_id, payment_channel,
                    facility_type, merchant_context_snapshot, request_key, request_fingerprint, source, status,
                    requested_amount, approved_amount, installment_months, recognized_performance_amount,
                    performance_basis_amount, performance_month, requested_at, approved_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'GENERAL', ?, ?, ?, 'SEED', 'APPROVED', ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                """, rows);

        // 실적은 기간의 모든 달(이번 달 포함)에 한 줄씩. 쓴 게 없는 달은 0원이다.
        List<Object[]> performanceRows = new ArrayList<>();
        for (SeededCard card : cards) {
            Map<YearMonth, Long> byMonth = performance.get(card.userCardId());
            if (byMonth == null) {
                continue;
            }
            for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(today)); month = month.plusMonths(1)) {
                performanceRows.add(new Object[] {card.userCardId(), month.atDay(1), byMonth.getOrDefault(month, 0L),
                        now, now});
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, performanceRows);
        return rows.size();
    }

    /** 체크카드 일상 소비(달마다 60만~70만 원으로 맞춤)에 넷플릭스와 큰 지출 1건을 더한다. */
    private static List<Payment> check(SeededCard card, String memberId, LocalDate from, LocalDate to) {
        List<Payment> daily = new ArrayList<>();
        List<Payment> payments = new ArrayList<>();
        LocalDate largeDay = YearMonth.from(to.plusDays(1)).minusMonths(2).atDay(15);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend) {
                pick(daily, card, memberId, day, 1, 60, List.of("STARBUCKS", "TWOSOME_PLACE", "PAUL_BASSETT", "HOLLYS"),
                        4_000, 6_990, 8, false);
                pick(daily, card, memberId, day, 2, 50, List.of("BURGER_KING", "MCDONALDS", "LOTTERIA"),
                        7_000, 11_990, 12, false);
                pick(daily, card, memberId, day, 3, 40, CONVENIENCE, 1_500, 11_990, 18, false);
                pick(daily, card, memberId, day, 4, 25, List.of("BAEMIN", "COUPANG_EATS", "YOGIYO"),
                        15_000, 29_990, 19, true);
            } else {
                pick(daily, card, memberId, day, 1, 50, List.of("STARBUCKS", "TWOSOME_PLACE"), 4_000, 6_990, 11, false);
                pick(daily, card, memberId, day, 5, 50, List.of("EMART", "HOMEPLUS"), 20_000, 69_990, 15, false);
                pick(daily, card, memberId, day, 6, 15, List.of("CGV", "MEGABOX"), 14_000, 29_990, 20, false);
                pick(daily, card, memberId, day, 7, 20, List.of("OLIVE_YOUNG"), 8_000, 39_990, 16, false);
            }
            if (day.getDayOfMonth() == 5) {
                payments.add(new Payment(card, day, 8, "NETFLIX", 13_500, 1, 9, true));
            }
            if (day.equals(largeDay)) {
                payments.add(new Payment(card, day, 9, "COUPANG", 1_290_000, 1, 21, true));
            }
        }
        payments.addAll(fitMonths(daily, card, memberId, from, to));
        return payments;
    }

    /**
     * 달마다 일상 소비 합계를 {@link #MONTHLY_MIN}~{@link #MONTHLY_MAX}(덜 찬 이번 달은 지난 날수만큼 줄인 값) 안으로 맞춘다.
     * 넘으면 해시 순서로 결제를 하나씩 빼고, 모자라면 편의점 결제(1,500~11,990원)를 하나씩 더한다. 개별 금액은 바꾸지 않는다.
     * 금액을 비율로 늘리면 마트 결제가 "직전 3개월 중앙값의 10배"를 넘어 큰 지출로 빠지기 때문이다(2026-11-05, 회원 1,000명
     * 측정: 비율 보정 8.1%, 이 방식 1.1%). 한 건이 최대 69,990원이라 꽉 찬 달은 늘 범위 안에 든다.
     */
    private static List<Payment> fitMonths(List<Payment> daily, SeededCard card, String memberId, LocalDate from,
            LocalDate to) {
        List<Payment> fitted = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            YearMonth current = month;
            int covered = month.equals(YearMonth.from(to)) ? to.getDayOfMonth() : month.lengthOfMonth();
            long min = MONTHLY_MIN * covered / month.lengthOfMonth();
            long max = MONTHLY_MAX * covered / month.lengthOfMonth();
            List<Payment> kept = new ArrayList<>(daily.stream()
                    .filter(payment -> YearMonth.from(payment.date()).equals(current))
                    .sorted(Comparator.comparingLong(payment -> hash(memberId, payment.date(), 100 + payment.slot())))
                    .toList());
            long sum = kept.stream().mapToLong(Payment::amount).sum();
            while (sum > max && !kept.isEmpty()) {
                sum -= kept.remove(kept.size() - 1).amount();
            }
            for (int extra = 0; sum < min; extra++) {
                long hash = hash(memberId, month.atDay(1), 200 + extra);
                LocalDate day = month.atDay(1 + (int) Math.floorMod(hash, (long) covered));
                String brand = CONVENIENCE.get((int) Math.floorMod(hash >>> 8, (long) CONVENIENCE.size()));
                long amount = 1_500 + Math.floorMod(hash >>> 16, (11_990L - 1_500) / 10 + 1) * 10;
                kept.add(new Payment(card, day, 20 + extra, brand, amount, 1, 13, false));
                sum += amount;
            }
            fitted.addAll(kept);
        }
        return fitted;
    }

    /** 신용카드 소비. 잔고예보 생활비에는 들어가지 않고 소비 리포트·실적에 보인다. */
    private static List<Payment> credit(SeededCard card, String memberId, LocalDate from, LocalDate to) {
        List<Payment> payments = new ArrayList<>();
        LocalDate installmentDay = YearMonth.from(to.plusDays(1)).minusMonths(3).atDay(20);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY) {
                pick(payments, card, memberId, day, 11, 100, List.of("SSG_COM", "COUPANG", "KYOBO"),
                        20_000, 149_990, 14, true);
            }
            if (day.getDayOfMonth() == 3 || day.getDayOfMonth() == 17) {
                pick(payments, card, memberId, day, 12, 100, List.of("GS_CALTEX", "SK_ENERGY"), 50_000, 79_990, 8, false);
            }
            if (day.getDayOfMonth() == 25) {
                payments.add(new Payment(card, day, 13, "SKT", 55_000, 1, 10, true));
            }
            if (day.equals(installmentDay)) {
                payments.add(new Payment(card, day, 14, "COUPANG", 900_000, 3, 22, true));
            }
        }
        return payments;
    }

    /** percent% 확률(해시)로 그날 한 건을 넣는다. 가맹점과 금액(10원 단위, min~max)도 해시로 고른다. */
    private static void pick(List<Payment> payments, SeededCard card, String memberId, LocalDate day, int slot,
            int percent, List<String> brands, long min, long max, int hour, boolean online) {
        long hash = hash(memberId, day, slot);
        if (Math.floorMod(hash, 100) >= percent) {
            return;
        }
        String brand = brands.get((int) Math.floorMod(hash >>> 8, (long) brands.size()));
        long steps = (max - min) / 10 + 1;
        long amount = min + Math.floorMod(hash >>> 16, steps) * 10;
        payments.add(new Payment(card, day, slot, brand, amount, 1, hour, online));
    }

    private static long hash(String memberId, LocalDate day, int slot) {
        byte[] digest = sha256(memberId + ":" + day + ":" + slot);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xff);
        }
        return value;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 카탈로그에 없는 브랜드가 있으면 넣지 않고 멈춘다. 다른 브랜드로 바꿔 넣지 않는다. */
    private Map<String, Long> merchants() {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT brand_code, merchant_id FROM merchant", rs -> {
            if (BRANDS.containsKey(rs.getString(1))) {
                ids.put(rs.getString(1), rs.getLong(2));
            }
        });
        List<String> missing = BRANDS.keySet().stream().filter(brand -> !ids.containsKey(brand)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new PersonaSeedService.SeedFailure("과거 소비에 쓸 가맹점이 없습니다(" + String.join(", ", missing)
                    + "). 카드 카탈로그 시드(be/seed/01·02)를 먼저 넣었는지 확인하세요.");
        }
        return ids;
    }

    /** @param slot 같은 날 같은 카드의 결제를 나누는 순번(결제 요청 번호를 만들 때 씀) */
    private record Payment(SeededCard card, LocalDate date, int slot, String brand, long amount, int months, int hour,
            boolean online) {}
}
