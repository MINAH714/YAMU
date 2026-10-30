package com.yamu.be.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PinHasher;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "spring.application.name=yamu-user-seed-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeedUserDataTest extends MySqlTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void encryptsAndSeedsTheFixtureAndResetsOnlyItsOwnerAtomically() throws Exception {
        CryptoKeys keys = new CryptoKeys(new CryptoProperties(1, Map.of(1, newKey()), newKey(), newKey()));
        PersonalDataCipher cipher = new PersonalDataCipher(keys, new SecureRandom());
        LookupHasher hashes = new LookupHasher(keys);
        PinHasher pins = new PinHasher(keys);
        String member = "y000000001@yamu.app";
        String financialKey = UUID.randomUUID().toString();
        String accountNumber = "0880000000000001";
        // UTC October 31 is already November in Korea: previous performance month is October.
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new FileSystemResource(Path.of("seed", "01-card-catalog.sql")), StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO app_user (name,created_at,updated_at) VALUES ('other owner', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
            SeedUserData seed = new SeedUserData(connection, cipher, hashes, pins, clock);
            seed.load(member, financialKey, "088", accountNumber, "012345", false);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_card", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT linked_account_id) FROM user_card", Integer.class)).isEqualTo(1);
            var user = jdbc.queryForMap("SELECT * FROM app_user WHERE ssafy_member_id=?", member);
            assertThat(cipher.decrypt((byte[]) user.get("ssafy_user_key"), CipherField.APP_USER_SSAFY_USER_KEY)).isEqualTo(financialKey);
            assertThat(cipher.decrypt((byte[]) user.get("phone_enc"), CipherField.APP_USER_PHONE)).isEqualTo("01000000001");
            assertThat(user.get("phone_hash")).isEqualTo(hashes.phone("01000000001"));
            assertThat(pins.matches("012345", (String) user.get("pin_hash"))).isTrue();
            var account = jdbc.queryForMap("SELECT * FROM linked_account");
            assertThat(cipher.decrypt((byte[]) account.get("account_no_enc"), CipherField.LINKED_ACCOUNT_NO)).isEqualTo(accountNumber);
            assertThat(account.get("account_no_hash")).isEqualTo(hashes.account("088", accountNumber));
            for (var cardRow : jdbc.queryForList("SELECT card_no_enc,card_no_hash,status,issuer_status FROM user_card")) {
                String rawCard = cipher.decrypt((byte[]) cardRow.get("card_no_enc"), CipherField.USER_CARD_NO);
                assertThat(cardRow.get("card_no_hash")).isEqualTo(hashes.card(rawCard));
                assertThat(cardRow.get("status")).isEqualTo("ACTIVE");
                assertThat(cardRow.get("issuer_status")).isEqualTo("NORMAL");
            }
            assertThat(jdbc.queryForList("""
                    SELECT recognized_amount FROM card_monthly_performance
                    WHERE performance_month='2026-10-01' ORDER BY recognized_amount
                    """, Long.class)).containsExactly(0L, 400000L, 400000L);
            assertThat(jdbc.queryForObject("""
                    SELECT SUM(recognized_amount) FROM card_monthly_performance WHERE performance_month='2026-11-01'
                    """, Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_monthly_performance", Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monthly_limit_usage", Integer.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE source='SEED'", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT SUM(recognized_performance_amount) FROM payment_transaction", Long.class))
                    .isEqualTo(800000L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE fin_transaction_no IS NOT NULL", Integer.class))
                    .isZero();

            var firstCards = jdbc.queryForList("SELECT * FROM user_card ORDER BY user_card_id");
            assertThat(jdbc.queryForList("""
                    SELECT p.product_name FROM user_card c JOIN card_product p USING (card_product_id)
                    ORDER BY c.user_card_id
                    """, String.class)).containsExactly("신한카드 Mr.Life", "현대카드 ZERO Edition3 (할인형)", "신한카드 처음");
            // A legacy fixture is never silently kept or overwritten by a normal rerun.
            jdbc.update("UPDATE card_product SET product_name='다이소 신한카드' WHERE product_name='신한카드 처음'");
            assertThatThrownBy(() -> seed.load(member, financialKey, "088", accountNumber, "012345", false))
                    .isInstanceOf(SeedUserData.ResetRequiredException.class);
            assertThat(jdbc.queryForList("SELECT * FROM user_card ORDER BY user_card_id"))
                    .usingRecursiveComparison().isEqualTo(firstCards);
            jdbc.update("UPDATE card_product SET product_name='신한카드 처음' WHERE product_name='다이소 신한카드'");
            jdbc.update("UPDATE monthly_limit_usage SET used_value=1");
            seed.load(member, financialKey, "088", accountNumber, "012345", false);
            assertThat(jdbc.queryForList("SELECT * FROM user_card ORDER BY user_card_id"))
                    .usingRecursiveComparison().isEqualTo(firstCards);
            assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage", Long.class)).isEqualTo(10L);

            seed.load(member, financialKey, "088", accountNumber, "012345", true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_card", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT SUM(used_value) FROM monthly_limit_usage", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE name='other owner'", Integer.class)).isEqualTo(1);

            // Failure after deleting this fixture must restore its previous rows, not leave a half-reset user.
            jdbc.update("DELETE FROM payment_transaction");
            jdbc.update("DELETE FROM merchant WHERE brand_code='YAMU_TEST_RESTAURANT'");
            var beforeFailure = jdbc.queryForList("SELECT * FROM user_card ORDER BY user_card_id");
            assertThatThrownBy(() -> seed.load(member, financialKey, "088", accountNumber, "012345", true))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForList("SELECT * FROM user_card ORDER BY user_card_id"))
                    .usingRecursiveComparison().isEqualTo(beforeFailure);

            jdbc.update("UPDATE app_user SET name='not the fixture' WHERE ssafy_member_id=?", member);
            assertThatThrownBy(() -> seed.load(member, financialKey, "088", accountNumber, "012345", true))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_card", Integer.class)).isEqualTo(3);
        }
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
