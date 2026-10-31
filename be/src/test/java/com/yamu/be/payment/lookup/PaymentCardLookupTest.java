package com.yamu.be.payment.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL로 추천 후보 조회를 확인한다. 남의 카드·등록 전·해제 카드가 섞이지 않는지가 핵심이다. */
@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-payment-card-lookup-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCardLookupTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentCardLookup lookup;

    OwnershipFixtures fixtures;
    long owner;
    long other;
    long account;
    long creditProduct;
    long checkProduct;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        other = fixtures.user();
        account = fixtures.account(owner);
        creditProduct = fixtures.product();
        checkProduct = fixtures.product();
        jdbc.update("update card_product set card_type = 'CHECK' where card_product_id = ?", checkProduct);
    }

    @Test
    void ACTIVE_카드만_돌려주고_INACTIVE_UNREGISTERED_PENDING과_남의_카드는_뺀다() {
        long active = fixtures.card(owner, creditProduct, account, "ACTIVE");
        fixtures.card(owner, creditProduct, account, "INACTIVE");
        fixtures.card(owner, creditProduct, account, "UNREGISTERED");
        fixtures.card(owner, creditProduct, null, "PENDING");
        long othersActive = fixtures.card(other, creditProduct, null, "ACTIVE");

        List<ActiveCard> cards = lookup.findActiveCards(owner);

        assertThat(cards).extracting(ActiveCard::userCardId).containsExactly(active);
        assertThat(lookup.findActiveCards(other)).extracting(ActiveCard::userCardId).containsExactly(othersActive);
        assertThat(lookup.countActiveCards(owner)).isEqualTo(1);
    }

    @Test
    void 기본_카드_표시_순서_순서_없는_카드_카드_번호_차례로_정렬한다() {
        long noOrder = fixtures.card(owner, creditProduct, account, "ACTIVE");
        long second = fixtures.card(owner, creditProduct, account, "ACTIVE");
        long first = fixtures.card(owner, creditProduct, account, "ACTIVE");
        long defaultCard = fixtures.card(owner, creditProduct, account, "ACTIVE");
        jdbc.update("update user_card set display_order = 5 where user_card_id = ?", second);
        jdbc.update("update user_card set display_order = 2 where user_card_id = ?", first);
        jdbc.update("update user_card set is_default = true where user_card_id = ?", defaultCard);

        List<ActiveCard> cards = lookup.findActiveCards(owner);

        assertThat(cards).extracting(ActiveCard::userCardId).containsExactly(defaultCard, first, second, noOrder);
        assertThat(cards.get(0).defaultCard()).isTrue();
        assertThat(cards.get(1).displayOrder()).isEqualTo(2);
        assertThat(cards.get(3).displayOrder()).isNull();
    }

    @Test
    void 카드_종류_카드사_상태_별칭_끝자리_상품_정보를_같이_돌려준다() {
        long credit = fixtures.card(owner, creditProduct, account, "ACTIVE");
        long check = fixtures.card(owner, checkProduct, account, "ACTIVE");
        jdbc.update("update user_card set card_alias = '메인', issuer_status = 'SUSPENDED' where user_card_id = ?",
                credit);

        List<ActiveCard> cards = lookup.findActiveCards(owner);

        ActiveCard creditCard = find(cards, credit);
        assertThat(creditCard.cardType()).isEqualTo(CardType.CREDIT);
        assertThat(creditCard.issuerStatus()).isEqualTo(CardIssuerStatus.SUSPENDED);
        assertThat(creditCard.alias()).isEqualTo("메인");
        assertThat(creditCard.last4()).isEqualTo("1234");
        assertThat(creditCard.issuerCode()).isEqualTo("TEST");
        assertThat(creditCard.productName()).isNotBlank();

        ActiveCard checkCard = find(cards, check);
        assertThat(checkCard.cardType()).isEqualTo(CardType.CHECK);
        assertThat(checkCard.issuerStatus()).isEqualTo(CardIssuerStatus.NORMAL);
        assertThat(checkCard.alias()).isNull();
    }

    private static ActiveCard find(List<ActiveCard> cards, long userCardId) {
        return cards.stream().filter(c -> c.userCardId() == userCardId).findFirst().orElseThrow();
    }
}
