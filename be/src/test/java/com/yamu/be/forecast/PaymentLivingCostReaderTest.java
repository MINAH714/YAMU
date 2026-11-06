package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 생활비 원천이 실제 MySQL의 체크카드 결제를 한국 날짜별로 더하는 것을 확인한다. 기간은 2026년 6월이다. */
@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-living-cost-reader-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentLivingCostReaderTest extends MySqlTestSupport {
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentLivingCostReader reader;

    OwnershipFixtures ownership;
    SpendingFixtures fixtures;
    long owner;
    long accountA;
    long accountB;
    long checkA;
    long creditA;
    long checkB;
    long merchant;

    @BeforeEach
    void setUp() {
        ownership = new OwnershipFixtures(jdbc);
        fixtures = new SpendingFixtures(jdbc);
        owner = ownership.user();
        accountA = ownership.account(owner);
        accountB = ownership.account(owner);
        LocalDate registered = LocalDate.of(2025, 12, 1);
        checkA = fixtures.card(owner, fixtures.product("CHECK"), accountA, registered);
        creditA = fixtures.card(owner, fixtures.product("CREDIT"), accountA, registered);
        checkB = fixtures.card(owner, fixtures.product("CHECK"), accountB, registered);
        merchant = fixtures.merchant();
    }

    @Test
    void 선택_계좌_체크카드_LIVING_결제를_한국_날짜별로_더하고_쓰지_않은_날은_0원_줄이다() {
        // 첫 YAMU 결제가 기간 앞이라 6월 전체를 REAL이 덮는다
        fixtures.approved(owner, checkA, fixtures.merchant(), 1_000L, LocalDate.of(2026, 5, 20), 12);
        fixtures.approved(owner, checkA, merchant, 10_000L, LocalDate.of(2026, 6, 3), 12);
        fixtures.approved(owner, checkA, fixtures.merchant(), 5_000L, LocalDate.of(2026, 6, 3), 18);
        // 한국 6/6 00:00은 UTC 6/5 15:00이다. 한국 날짜 6/6에 들어가야 한다
        fixtures.approved(owner, checkA, merchant, 7_000L, LocalDate.of(2026, 6, 6), 0);
        fixtures.approved(owner, creditA, merchant, 7_000L, LocalDate.of(2026, 6, 3), 12);           // 신용
        fixtures.approved(owner, checkA, merchant, 2_000_000L, LocalDate.of(2026, 6, 4), 12);        // LARGE
        fixtures.payment(owner, checkA, merchant, 3_000L, 1, SpendingFixtures.utc(LocalDate.of(2026, 6, 5), 12),
                "YAMU", "CANCELED");                                                                   // 취소
        fixtures.approved(owner, checkB, merchant, 9_000L, LocalDate.of(2026, 6, 3), 12);            // 고르지 않은 계좌
        long strangerAccount = strangerAccountWithSpending();

        List<LivingCostReader.DailySpending> days = reader.dailySpending(owner, List.of(accountA, strangerAccount),
                FROM, TO).days();

        assertThat(days).hasSize(30).allSatisfy(day -> {
            assertThat(day.accountId()).isEqualTo(accountA);
            assertThat(day.origin()).isEqualTo(LivingCostReader.Origin.REAL);
            assertThat(day.complete()).isTrue();
            assertThat(day.checkCardAmount()).isEqualTo(day.amount());
        });
        Map<LocalDate, Long> amounts = amounts(days, accountA, LivingCostReader.Origin.REAL);
        assertThat(amounts.get(LocalDate.of(2026, 6, 3))).isEqualTo(15_000L);
        assertThat(amounts.get(LocalDate.of(2026, 6, 4))).isZero();
        assertThat(amounts.get(LocalDate.of(2026, 6, 5))).isZero();
        assertThat(amounts.get(LocalDate.of(2026, 6, 6))).isEqualTo(7_000L);
        assertThat(amounts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(22_000L);
    }

    @Test
    void 같은_카드_가맹점_금액이_6개월_중_2개_달이면_넣고_3개_달이면_뺀다() {
        long twoMonths = fixtures.merchant();
        fixtures.approved(owner, checkA, twoMonths, 4_500L, LocalDate.of(2026, 5, 20), 12);
        fixtures.approved(owner, checkA, twoMonths, 4_500L, LocalDate.of(2026, 6, 10), 12);
        // 4월 결제를 사용자가 LARGE로 고쳤어도 반복 횟수에는 센다
        long threeMonths = fixtures.merchant();
        long april = fixtures.approved(owner, checkA, threeMonths, 9_900L, LocalDate.of(2026, 4, 10), 12);
        fixtures.classifyAs(april, "LARGE", "USER");
        fixtures.approved(owner, checkA, threeMonths, 9_900L, LocalDate.of(2026, 5, 10), 12);
        fixtures.approved(owner, checkA, threeMonths, 9_900L, LocalDate.of(2026, 6, 11), 12);
        // 6월의 6개월은 1~6월이다. 1·2·6월이면 3개 달이라 뺀다
        long januaryFebruary = fixtures.merchant();
        fixtures.approved(owner, checkA, januaryFebruary, 3_300L, LocalDate.of(2026, 1, 10), 12);
        fixtures.approved(owner, checkA, januaryFebruary, 3_300L, LocalDate.of(2026, 2, 10), 12);
        fixtures.approved(owner, checkA, januaryFebruary, 3_300L, LocalDate.of(2026, 6, 12), 12);
        // 12·1·6월이면 12월이 6개월 밖이라 2개 달이고 넣는다
        long decemberJanuary = fixtures.merchant();
        fixtures.approved(owner, checkA, decemberJanuary, 2_200L, LocalDate.of(2025, 12, 10), 12);
        fixtures.approved(owner, checkA, decemberJanuary, 2_200L, LocalDate.of(2026, 1, 11), 12);
        fixtures.approved(owner, checkA, decemberJanuary, 2_200L, LocalDate.of(2026, 6, 13), 12);
        // 같은 가맹점이라도 금액이 다르면 다른 지출이다
        fixtures.approved(owner, checkA, threeMonths, 9_800L, LocalDate.of(2026, 6, 14), 12);

        Map<LocalDate, Long> amounts = amounts(reader.dailySpending(owner, List.of(accountA), FROM, TO).days(),
                accountA, LivingCostReader.Origin.REAL);

        assertThat(amounts.get(LocalDate.of(2026, 6, 10))).isEqualTo(4_500L);
        assertThat(amounts.get(LocalDate.of(2026, 6, 11))).isZero();
        assertThat(amounts.get(LocalDate.of(2026, 6, 12))).isZero();
        assertThat(amounts.get(LocalDate.of(2026, 6, 13))).isEqualTo(2_200L);
        assertThat(amounts.get(LocalDate.of(2026, 6, 14))).isEqualTo(9_800L);
    }

    @Test
    void 같은_가격을_한_달에_여러_번_쓰는_일상_소비는_3개_달_이상이어도_넣는다() {
        // 4·5·6월 매달 월·수·금처럼 4,500원 커피를 세 번씩 산다(S15P31E103-295)
        long cafe = fixtures.merchant();
        for (int month = 4; month <= 6; month++) {
            for (int day : new int[] {3, 5, 8}) {
                fixtures.approved(owner, checkA, cafe, 4_500L, LocalDate.of(2026, month, day), 12);
            }
        }

        Map<LocalDate, Long> amounts = amounts(reader.dailySpending(owner, List.of(accountA), FROM, TO).days(),
                accountA, LivingCostReader.Origin.REAL);

        assertThat(amounts.get(LocalDate.of(2026, 6, 3))).isEqualTo(4_500L);
        assertThat(amounts.get(LocalDate.of(2026, 6, 5))).isEqualTo(4_500L);
        assertThat(amounts.get(LocalDate.of(2026, 6, 8))).isEqualTo(4_500L);
    }

    @Test
    void 매달_한_번이던_결제도_6개월_안에_두_번_나온_달이_있으면_넣는다() {
        // 4·5·6월 매달 13,500원인데 5월에 같은 금액을 한 번 더 썼다. 매달 한 번인 정기 결제로 보지 않는다
        long store = fixtures.merchant();
        fixtures.approved(owner, checkA, store, 13_500L, LocalDate.of(2026, 4, 10), 12);
        fixtures.approved(owner, checkA, store, 13_500L, LocalDate.of(2026, 5, 10), 12);
        fixtures.approved(owner, checkA, store, 13_500L, LocalDate.of(2026, 5, 25), 12);
        fixtures.approved(owner, checkA, store, 13_500L, LocalDate.of(2026, 6, 10), 12);
        // 두 번 나온 달(12월)이 6월의 6개월(1~6월) 밖이면 1·2·6월 매달 한 번이라 뺀다
        long older = fixtures.merchant();
        fixtures.approved(owner, checkA, older, 6_600L, LocalDate.of(2025, 12, 5), 12);
        fixtures.approved(owner, checkA, older, 6_600L, LocalDate.of(2025, 12, 20), 12);
        fixtures.approved(owner, checkA, older, 6_600L, LocalDate.of(2026, 1, 5), 12);
        fixtures.approved(owner, checkA, older, 6_600L, LocalDate.of(2026, 2, 5), 12);
        fixtures.approved(owner, checkA, older, 6_600L, LocalDate.of(2026, 6, 15), 12);

        Map<LocalDate, Long> amounts = amounts(reader.dailySpending(owner, List.of(accountA), FROM, TO).days(),
                accountA, LivingCostReader.Origin.REAL);

        assertThat(amounts.get(LocalDate.of(2026, 6, 10))).isEqualTo(13_500L);
        assertThat(amounts.get(LocalDate.of(2026, 6, 15))).isZero();
    }

    @Test
    void SEED는_첫_SEED_결제일부터_등록_전날까지_REAL은_첫_YAMU_결제일부터이고_그_출처_결제만_더한다() {
        long account = ownership.account(owner);
        long card = fixtures.card(owner, fixtures.product("CHECK"), account, LocalDate.of(2026, 6, 15));
        seed(card, 3_000L, LocalDate.of(2026, 5, 20));
        seed(card, 4_000L, LocalDate.of(2026, 6, 14));
        // 등록 뒤에 찍힌 SEED 결제는 REAL 줄에 섞지 않고 버린다(2026-11-04 결정)
        seed(card, 8_000L, LocalDate.of(2026, 6, 16));
        // 등록(6/15) 뒤 첫 YAMU 결제는 6/20이다. 6/15~6/19는 YAMU를 안 쓴 것인지 몰라 어느 출처의 줄도 없다
        fixtures.approved(owner, card, merchant, 6_000L, LocalDate.of(2026, 6, 20), 12);

        List<LivingCostReader.DailySpending> days = reader.dailySpending(owner, List.of(account),
                LocalDate.of(2026, 5, 1), TO).days();

        Map<LocalDate, Long> seedDays = amounts(days, account, LivingCostReader.Origin.SEED);
        Map<LocalDate, Long> realDays = amounts(days, account, LivingCostReader.Origin.REAL);
        assertThat(seedDays).hasSize(26);     // 5/20~6/14
        assertThat(seedDays.keySet()).allSatisfy(date ->
                assertThat(date).isBetween(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 6, 14)));
        assertThat(seedDays.get(LocalDate.of(2026, 5, 20))).isEqualTo(3_000L);
        assertThat(seedDays.get(LocalDate.of(2026, 6, 14))).isEqualTo(4_000L);
        assertThat(realDays).hasSize(11);     // 6/20~6/30
        assertThat(realDays.keySet()).allSatisfy(date ->
                assertThat(date).isBetween(LocalDate.of(2026, 6, 20), TO));
        assertThat(realDays.get(LocalDate.of(2026, 6, 20))).isEqualTo(6_000L);
        assertThat(days).noneSatisfy(day ->
                assertThat(day.date()).isBetween(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 19)));
        assertThat(days).allSatisfy(day -> assertThat(day.complete()).isTrue());
    }

    @Test
    void 조회_시작일보다_앞선_날은_주지_않고_등록_전_카드의_SEED는_오늘까지다() {
        long account = ownership.account(owner);
        long pending = fixtures.card(owner, fixtures.product("CHECK"), account, null);
        seed(pending, 3_000L, LocalDate.of(2026, 5, 20));
        seed(pending, 5_000L, LocalDate.of(2026, 6, 30));

        List<LivingCostReader.DailySpending> days = reader.dailySpending(owner, List.of(account), FROM, TO).days();

        assertThat(days).hasSize(30).allSatisfy(day -> assertThat(day.origin())
                .isEqualTo(LivingCostReader.Origin.SEED));
        Map<LocalDate, Long> amounts = amounts(days, account, LivingCostReader.Origin.SEED);
        assertThat(amounts.keySet()).allSatisfy(date -> assertThat(date).isBetween(FROM, TO));
        assertThat(amounts.get(TO)).isEqualTo(5_000L);
    }

    @Test
    void 체크카드를_등록만_하고_YAMU로_결제하지_않았으면_REAL_줄이_없다() {
        // 등록만 한 계좌를 0원·완료로 채우면 "생활비 0원, 신뢰도 HIGH"가 되어 위험을 가린다(2026-11-04 결정)
        long account = ownership.account(owner);
        fixtures.card(owner, fixtures.product("CHECK"), account, LocalDate.of(2026, 3, 1));

        assertThat(reader.dailySpending(owner, List.of(account), FROM, TO).days()).isEmpty();
    }

    @Test
    void 체크카드가_없는_계좌는_줄이_없다() {
        long creditOnly = ownership.account(owner);
        long credit = fixtures.card(owner, fixtures.product("CREDIT"), creditOnly, LocalDate.of(2025, 12, 1));
        fixtures.approved(owner, credit, merchant, 10_000L, LocalDate.of(2026, 6, 3), 12);

        assertThat(reader.dailySpending(owner, List.of(creditOnly), FROM, TO).days()).isEmpty();
    }

    @Test
    void 신용카드는_카드별로_같은_규칙으로_더하고_체크카드와_남의_카드는_섞지_않는다() {
        // S15P31E103-286: 선택 계좌 A가 출금 계좌인 신용카드 두 장
        long creditA2 = fixtures.card(owner, fixtures.product("CREDIT"), accountA, LocalDate.of(2025, 12, 1));
        long creditB = fixtures.card(owner, fixtures.product("CREDIT"), accountB, LocalDate.of(2025, 12, 1));
        fixtures.approved(owner, creditA, fixtures.merchant(), 1_000L, LocalDate.of(2026, 5, 20), 12);
        fixtures.approved(owner, creditA, merchant, 10_000L, LocalDate.of(2026, 6, 3), 12);
        fixtures.approved(owner, creditA, merchant, 2_000_000L, LocalDate.of(2026, 6, 4), 12);       // LARGE
        fixtures.payment(owner, creditA, merchant, 3_000L, 1, SpendingFixtures.utc(LocalDate.of(2026, 6, 5), 12),
                "YAMU", "CANCELED");                                                                    // 취소
        fixtures.approved(owner, creditA2, merchant, 6_000L, LocalDate.of(2026, 6, 10), 12);
        fixtures.approved(owner, checkA, merchant, 9_000L, LocalDate.of(2026, 6, 3), 12);             // 체크카드
        fixtures.approved(owner, creditB, merchant, 8_000L, LocalDate.of(2026, 6, 3), 12);            // 고르지 않은 계좌
        // 매달 한 번 같은 가맹점·금액(4·5·6월)은 반복 지출이라 뺀다
        long subscription = fixtures.merchant();
        for (int month = 4; month <= 6; month++) {
            fixtures.approved(owner, creditA, subscription, 17_000L, LocalDate.of(2026, month, 15), 12);
        }
        long strangerAccount = strangerAccountWithSpending();
        long stranger = jdbc.queryForObject("SELECT user_id FROM linked_account WHERE linked_account_id = ?",
                Long.class, strangerAccount);
        long strangerCredit = fixtures.card(stranger, fixtures.product("CREDIT"), strangerAccount,
                LocalDate.of(2025, 12, 1));
        fixtures.approved(stranger, strangerCredit, merchant, 50_000L, LocalDate.of(2026, 6, 3), 12);

        CreditSpendingReader.CreditSpending result = reader.creditDailySpending(owner,
                List.of(accountA, strangerAccount), FROM, TO);

        assertThat(result.userCardIds()).containsExactly(creditA, creditA2);
        Map<LocalDate, Long> first = creditAmounts(result, creditA);
        assertThat(first).hasSize(30);     // 첫 결제(5/20)가 기간 앞이라 6월 전체
        assertThat(first.get(LocalDate.of(2026, 6, 3))).isEqualTo(10_000L);
        assertThat(first.get(LocalDate.of(2026, 6, 4))).isZero();
        assertThat(first.get(LocalDate.of(2026, 6, 5))).isZero();
        assertThat(first.get(LocalDate.of(2026, 6, 15))).isZero();
        assertThat(first.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);
        Map<LocalDate, Long> second = creditAmounts(result, creditA2);
        assertThat(second).hasSize(21);    // 첫 YAMU 결제 6/10부터
        assertThat(second.get(LocalDate.of(2026, 6, 10))).isEqualTo(6_000L);
        assertThat(result.days()).allSatisfy(day -> assertThat(day.origin()).isEqualTo(LivingCostReader.Origin.REAL));
    }

    @Test
    void 선택_계좌에_신용카드가_없으면_빈_목록이다() {
        assertThat(reader.creditDailySpending(owner, List.of(accountB), FROM, TO))
                .isEqualTo(new CreditSpendingReader.CreditSpending(List.of(), List.of()));
    }

    private static Map<LocalDate, Long> creditAmounts(CreditSpendingReader.CreditSpending spending, long userCardId) {
        return spending.days().stream().filter(day -> day.userCardId() == userCardId)
                .collect(Collectors.toMap(CreditSpendingReader.CardDailySpending::date,
                        CreditSpendingReader.CardDailySpending::amount));
    }

    private void seed(long card, long amount, LocalDate day) {
        fixtures.payment(owner, card, merchant, amount, 1, SpendingFixtures.utc(day, 12), "SEED", "APPROVED");
    }

    /** 남의 계좌·체크카드·결제. 내 사용자로 읽으면 줄이 나오면 안 된다. */
    private long strangerAccountWithSpending() {
        long stranger = ownership.user();
        long account = ownership.account(stranger);
        long card = fixtures.card(stranger, fixtures.product("CHECK"), account, LocalDate.of(2025, 12, 1));
        fixtures.approved(stranger, card, merchant, 50_000L, LocalDate.of(2026, 6, 3), 12);
        return account;
    }

    private static Map<LocalDate, Long> amounts(List<LivingCostReader.DailySpending> days, long accountId,
            LivingCostReader.Origin origin) {
        return days.stream().filter(day -> day.accountId() == accountId && day.origin() == origin)
                .collect(Collectors.toMap(LivingCostReader.DailySpending::date,
                        LivingCostReader.DailySpending::amount));
    }
}
