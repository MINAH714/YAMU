package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 소비 분류를 실제 MySQL의 payment_transaction에 쓰는 것을 확인한다. 날짜는 한국 날짜다. */
@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-spending-classifier-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpendingClassifierTest extends MySqlTestSupport {
    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);

    @Autowired JdbcTemplate jdbc;
    @Autowired SpendingClassifier classifier;

    SpendingFixtures fixtures;
    long owner;
    long checkCard;
    long creditCard;
    long merchant;

    @BeforeEach
    void setUp() {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        fixtures = new SpendingFixtures(jdbc);
        owner = ownership.user();
        long account = ownership.account(owner);
        checkCard = fixtures.card(owner, fixtures.product("CHECK"), account, DAY.minusYears(1));
        creditCard = fixtures.card(owner, fixtures.product("CREDIT"), account, DAY.minusYears(1));
        merchant = fixtures.merchant();
    }

    @Test
    void 할부는_LARGE이고_일시불은_LIVING이다_신용과_체크_모두() {
        long installment = fixtures.payment(owner, creditCard, merchant, 30_000L, 2, SpendingFixtures.utc(DAY, 12),
                "YAMU", "APPROVED");
        long lumpSumCredit = fixtures.approved(owner, creditCard, merchant, 30_000L, DAY, 13);
        long lumpSumCheck = fixtures.approved(owner, checkCard, merchant, 30_000L, DAY, 14);

        assertThat(classifier.classify(owner)).isEqualTo(3);

        assertThat(fixtures.spendingClass(installment)).isEqualTo("LARGE");
        assertThat(fixtures.spendingClass(lumpSumCredit)).isEqualTo("LIVING");
        assertThat(fixtures.spendingClass(lumpSumCheck)).isEqualTo("LIVING");
        assertThat(fixtures.spendingClassSource(lumpSumCheck)).isEqualTo("AUTO");
    }

    @Test
    void 한_건_100만_원은_LIVING이고_100만_1원부터_LARGE다() {
        long exactly = fixtures.approved(owner, checkCard, merchant, 1_000_000L, DAY, 12);
        long over = fixtures.approved(owner, checkCard, merchant, 1_000_001L, DAY, 13);

        classifier.classify(owner);

        assertThat(fixtures.spendingClass(exactly)).isEqualTo("LIVING");
        assertThat(fixtures.spendingClass(over)).isEqualTo("LARGE");
    }

    @Test
    void 직전_3개월_LIVING_중앙값의_10배_이상이면_LARGE다() {
        for (int i = 0; i < 5; i++) {
            fixtures.approved(owner, checkCard, merchant, 10_000L, DAY.minusDays(10 + i), 12);
        }
        long below = fixtures.approved(owner, checkCard, merchant, 99_999L, DAY, 12);
        // 앞 결제(99,999원)도 LIVING이라 표본이 6건(10,000×5, 99,999)이 되고 중앙값은 10,000원 그대로다
        long atTenTimes = fixtures.approved(owner, checkCard, merchant, 100_000L, DAY, 13);

        classifier.classify(owner);

        assertThat(fixtures.spendingClass(below)).isEqualTo("LIVING");
        assertThat(fixtures.spendingClass(atTenTimes)).isEqualTo("LARGE");
    }

    @Test
    void 직전_LIVING이_4건이면_중앙값_기준을_쓰지_않는다() {
        for (int i = 0; i < 4; i++) {
            fixtures.approved(owner, checkCard, merchant, 10_000L, DAY.minusDays(10 + i), 12);
        }
        long tenTimes = fixtures.approved(owner, checkCard, merchant, 500_000L, DAY, 12);

        classifier.classify(owner);

        assertThat(fixtures.spendingClass(tenTimes)).isEqualTo("LIVING");
    }

    @Test
    void 승인_시각_3개월보다_앞선_LIVING은_중앙값_표본이_아니다() {
        // 6/10 12:00 결제의 표본은 3/10 12:00부터다. 3/10 11:00 결제 5건은 빠지고 3/10 12:00 결제 1건만 남는다
        for (int i = 0; i < 5; i++) {
            fixtures.approved(owner, checkCard, merchant, 10_000L, DAY.minusMonths(3), 11);
        }
        fixtures.approved(owner, checkCard, merchant, 10_000L, DAY.minusMonths(3), 12);
        long target = fixtures.approved(owner, checkCard, merchant, 500_000L, DAY, 12);

        classifier.classify(owner);

        assertThat(fixtures.spendingClass(target)).isEqualTo("LIVING");
    }

    @Test
    void 사용자가_고친_분류는_덮지_않고_취소_결제는_분류하지_않는다() {
        long userLiving = fixtures.payment(owner, checkCard, merchant, 2_000_000L, 1, SpendingFixtures.utc(DAY, 12),
                "YAMU", "APPROVED");
        fixtures.classifyAs(userLiving, "LIVING", "USER");
        long userLarge = fixtures.approved(owner, checkCard, merchant, 5_000L, DAY, 13);
        fixtures.classifyAs(userLarge, "LARGE", "USER");
        long canceled = fixtures.payment(owner, checkCard, merchant, 5_000L, 1, SpendingFixtures.utc(DAY, 14),
                "YAMU", "CANCELED");

        assertThat(classifier.classify(owner)).isZero();

        assertThat(fixtures.spendingClass(userLiving)).isEqualTo("LIVING");
        assertThat(fixtures.spendingClassSource(userLiving)).isEqualTo("USER");
        assertThat(fixtures.spendingClass(userLarge)).isEqualTo("LARGE");
        assertThat(fixtures.spendingClassSource(userLarge)).isEqualTo("USER");
        assertThat(fixtures.spendingClass(canceled)).isNull();
    }

    @Test
    void 두_번_돌려도_결과가_같고_두_번째는_쓰지_않는다() {
        for (int i = 0; i < 5; i++) {
            fixtures.approved(owner, checkCard, merchant, 10_000L, DAY.minusDays(10 + i), 12);
        }
        long large = fixtures.approved(owner, checkCard, merchant, 200_000L, DAY, 12);
        long living = fixtures.approved(owner, checkCard, merchant, 20_000L, DAY, 13);

        assertThat(classifier.classify(owner)).isEqualTo(7);
        assertThat(classifier.classify(owner)).isZero();

        assertThat(fixtures.spendingClass(large)).isEqualTo("LARGE");
        assertThat(fixtures.spendingClass(living)).isEqualTo("LIVING");
    }

    @Test
    void 다른_사용자의_결제는_분류하지_않는다() {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        long stranger = ownership.user();
        long strangerCard = fixtures.card(stranger, fixtures.product("CHECK"), ownership.account(stranger),
                DAY.minusYears(1));
        long strangers = fixtures.approved(stranger, strangerCard, merchant, 10_000L, DAY, 12);
        long mine = fixtures.approved(owner, checkCard, merchant, 10_000L, DAY, 12);

        classifier.classify(owner);

        assertThat(fixtures.spendingClass(mine)).isEqualTo("LIVING");
        assertThat(fixtures.spendingClass(strangers)).isNull();
    }
}
