package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-card-lookup-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardQueryServiceTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired CardQueryService service;

    OwnershipFixtures fixtures;
    long owner;
    long product;
    long account;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        product = fixtures.product();
        account = fixtures.account(owner);
    }

    // ───────── 등록 상태 ─────────

    @Test
    void 보유_카드_목록에_보이는_ACTIVE와_INACTIVE_카드는_돌려준다() {
        long active = fixtures.card(owner, product, account, "ACTIVE");
        long inactive = fixtures.card(owner, product, null, "INACTIVE");

        jdbc.update("UPDATE user_card SET payment_day = 25 WHERE user_card_id = ?", active);

        assertThat(service.requireRegisteredCard(owner, active)).isEqualTo(new UserCardRef(active, product, account,
                "1234", CardRegistrationStatus.ACTIVE, CardIssuerStatus.NORMAL, CardType.CREDIT, 25, 14));
        assertThat(service.requireRegisteredCard(owner, inactive).linkedAccountId()).isNull();
    }

    @Test
    void 등록_전이나_등록_해제한_카드는_404다() {
        long pending = fixtures.card(owner, product, account, "PENDING");
        long unregistered = fixtures.card(owner, product, account, "UNREGISTERED");

        assertNotFound(() -> service.requireRegisteredCard(owner, pending));
        assertNotFound(() -> service.requireRegisteredCard(owner, unregistered));
    }

    @Test
    void 남의_카드는_있어도_없는_것처럼_404다() {
        long stranger = fixtures.user();
        long strangerCard = fixtures.card(stranger, product, fixtures.account(stranger), "ACTIVE");

        assertNotFound(() -> service.requireRegisteredCard(owner, strangerCard));
    }

    @Test
    void 없는_번호나_빈_번호는_404다() {
        assertNotFound(() -> service.requireRegisteredCard(owner, Long.MAX_VALUE));
        assertNotFound(() -> service.requireRegisteredCard(owner, null));
        assertNotFound(() -> service.requireRegisteredCard(null, fixtures.card(owner, product, account, "ACTIVE")));
    }

    // ───────── 카드 종류·결제일 (잔고예보) ─────────

    @Test
    void 체크카드는_결제일과_마감_일수를_null로_준다() {
        long checkProduct = jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE card_product_id = ?",
                Long.class, fixtures.product());
        jdbc.update("UPDATE card_product SET card_type = 'CHECK', billing_cutoff_days = NULL WHERE card_product_id = ?",
                checkProduct);
        long check = fixtures.card(owner, checkProduct, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = 10 WHERE user_card_id = ?", check);

        UserCardRef ref = service.requireRegisteredCard(owner, check);

        assertThat(ref.cardType()).isEqualTo(CardType.CHECK);
        assertThat(ref.paymentDay()).isNull();
        assertThat(ref.billingCutoffDays()).isNull();
    }

    // ───────── 404 대신 empty (잔고예보) ─────────

    @Test
    void findRegisteredCard는_쓸_수_있는_카드면_같은_값을_주고_아니면_예외_없이_empty다() {
        long active = fixtures.card(owner, product, account, "ACTIVE");
        long unregistered = fixtures.card(owner, product, account, "UNREGISTERED");
        long pending = fixtures.card(owner, product, account, "PENDING");
        long stranger = fixtures.user();
        long strangerCard = fixtures.card(stranger, product, fixtures.account(stranger), "ACTIVE");

        assertThat(service.findRegisteredCard(owner, active)).contains(service.requireRegisteredCard(owner, active));
        assertThat(service.findRegisteredCard(owner, unregistered)).isEmpty();
        assertThat(service.findRegisteredCard(owner, pending)).isEmpty();
        assertThat(service.findRegisteredCard(owner, strangerCard)).isEmpty();
        assertThat(service.findRegisteredCard(owner, Long.MAX_VALUE)).isEmpty();
        assertThat(service.findRegisteredCard(owner, null)).isEmpty();
        assertThat(service.findRegisteredCard(null, active)).isEmpty();
    }

    // ───────── 카드사 상태 ─────────

    @Test
    void 카드사_정지_만료는_거절하지_않고_상태로_넘긴다() {
        long suspended = fixtures.card(owner, product, account, "ACTIVE");
        long expired = fixtures.card(owner, product, account, "ACTIVE");
        jdbc.update("UPDATE user_card SET issuer_status = 'SUSPENDED' WHERE user_card_id = ?", suspended);
        jdbc.update("UPDATE user_card SET issuer_status = 'EXPIRED' WHERE user_card_id = ?", expired);

        assertThat(service.requireRegisteredCard(owner, suspended).issuerStatus()).isEqualTo(CardIssuerStatus.SUSPENDED);
        assertThat(service.requireRegisteredCard(owner, expired).issuerStatus()).isEqualTo(CardIssuerStatus.EXPIRED);
    }

    @Test
    void 카드사_상태는_기본이_NORMAL이고_정해진_값만_들어간다() {
        long card = fixtures.card(owner, product, account, "ACTIVE");

        assertThat(jdbc.queryForObject("SELECT issuer_status FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo("NORMAL");
        assertRejectedBy(3819, () -> jdbc.update("UPDATE user_card SET issuer_status = 'LOST' WHERE user_card_id = ?", card));
        assertRejectedBy(1048, () -> jdbc.update("UPDATE user_card SET issuer_status = NULL WHERE user_card_id = ?", card));
    }

    @Test
    void 두_상태_칼럼의_설명이_서로를_구분한다() {
        String sql = """
                SELECT column_comment FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'user_card' AND column_name = ?
                """;
        assertThat(jdbc.queryForObject(sql, String.class, "status")).startsWith("YAMU 등록 상태").contains("issuer_status");
        assertThat(jdbc.queryForObject(sql, String.class, "issuer_status")).startsWith("카드사(발급사)가 정한 상태").contains("status");
        assertThat(jdbc.queryForMap("""
                SELECT column_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'user_card' AND column_name = 'status'
                """)).containsEntry("COLUMN_TYPE", "varchar(20)").containsEntry("IS_NULLABLE", "NO")
                .containsEntry("COLUMN_DEFAULT", null);
    }

    /** MySQL 오류 번호로 확인한다. CHECK 위반(3819)은 Spring이 분류하지 못해 예외 종류가 일정하지 않다. 1048은 NOT NULL 위반. */
    private static void assertRejectedBy(int mysqlErrorCode, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(((DataAccessException) failure).getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(mysqlErrorCode)));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
