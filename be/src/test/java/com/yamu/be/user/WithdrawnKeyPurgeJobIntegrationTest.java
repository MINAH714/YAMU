package com.yamu.be.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.global.job.ManualJob;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** 탈퇴 30일 뒤 재가입 열쇠 정리(S15P31E103-380)를 실제 MySQL로 확인한다. 탈퇴한 행은 탈퇴 API가 남기는 모양대로 직접 넣는다. */
@TestPropertySource(properties = {
        "spring.application.name=yamu-withdrawn-key-purge-test",
        "yamu.user.withdrawn-key-purge.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WithdrawnKeyPurgeJobIntegrationTest extends MySqlTestSupport {

    private static final String CARD_NO = "9410000000000011";
    private static final String ACCOUNT_NO = "0881541149756547";

    @Autowired WithdrawnKeyPurgeJob job;
    @Autowired List<ManualJob> manualJobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;

    Instant now;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM app_user");
        now = Instant.now();
    }

    @Test
    void 탈퇴하고_30일이_지난_사람만_재가입_열쇠와_카드번호를_지우고_행은_남긴다() {
        long old = withdrawn("01011112222", "yold@yamu.app", Duration.ofDays(31));
        long oldCard = card(old);
        long recent = withdrawn("01033334444", "yrecent@yamu.app", Duration.ofDays(29));
        long recentCard = card(recent);
        long pending = pending("01055556666");
        byte[] oldCardHash = cardNoHash(oldCard);

        JobRunReport report = job.run(new JobRunCommand(now, null, false));

        assertThat(report.results()).extracting(JobRunReport.Item::userId).containsExactly(old);
        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(1);

        Map<String, Object> purged = user(old);
        assertThat(purged.get("status")).isEqualTo("WITHDRAWN");
        assertThat(purged.get("phone_hash")).isNull();
        assertThat(purged.get("birth_date")).isNull();
        assertThat(purged.get("withdrawn_name_hash")).isNull();
        assertThat(purged.get("ssafy_member_id")).isNull();
        assertThat(purged.get("ssafy_user_key")).isNull();
        Map<String, Object> purgedCard = jdbc.queryForMap("SELECT * FROM user_card WHERE user_card_id = ?", oldCard);
        assertThat((byte[]) purgedCard.get("card_no_hash")).isNotEqualTo(oldCardHash);
        assertThat(cipher.decrypt((byte[]) purgedCard.get("card_no_enc"), CipherField.USER_CARD_NO)).isNotEqualTo(CARD_NO);
        assertThat(purgedCard.get("issuer_account_hash")).isNull();
        assertThat(purgedCard.get("issuer_account_bank_code")).isNull();
        assertThat(purgedCard.get("issuer_account_last4")).isNull();
        assertThat(purgedCard.get("card_last4")).isEqualTo("0011");

        // 30일 안의 탈퇴자와 탈퇴하지 않은 사람은 그대로
        assertThat((byte[]) user(recent).get("phone_hash")).isEqualTo(lookupHasher.phone("01033334444"));
        assertThat(user(recent).get("ssafy_member_id")).isEqualTo("yrecent@yamu.app");
        assertThat((byte[]) cardNoHash(recentCard)).isEqualTo(lookupHasher.card(CARD_NO));
        assertThat((byte[]) user(pending).get("phone_hash")).isEqualTo(lookupHasher.phone("01055556666"));

        // 같은 사람을 휴대폰·생년월일로 다시 찾을 수 없다 → 재가입이 아닌 새 회원 경로
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone_hash = ?", Integer.class,
                lookupHasher.phone("01011112222"))).isZero();

        // 다시 돌려도 대상이 없다(멱등)
        assertThat(job.run(new JobRunCommand(now, null, false)).targets()).isZero();
    }

    @Test
    void dryRun은_대상만_보여_주고_아무것도_바꾸지_않으며_userId로_한_명만_고를_수_있다() {
        long a = withdrawn("01011112222", "ya@yamu.app", Duration.ofDays(40));
        long b = withdrawn("01033334444", "yb@yamu.app", Duration.ofDays(40));

        JobRunReport dry = job.run(new JobRunCommand(now, null, true));
        assertThat(dry.results()).extracting(JobRunReport.Item::result).containsOnly("DRY_RUN");
        assertThat(dry.targets()).isEqualTo(2);
        assertThat(user(a).get("ssafy_member_id")).isEqualTo("ya@yamu.app");

        JobRunReport onlyB = job.run(new JobRunCommand(now, b, false));
        assertThat(onlyB.results()).extracting(JobRunReport.Item::userId).containsExactly(b);
        assertThat(user(b).get("ssafy_member_id")).isNull();
        assertThat(user(a).get("ssafy_member_id")).isEqualTo("ya@yamu.app");
    }

    @Test
    void 재가입으로_열쇠가_옮겨진_옛_행은_카드사_계좌_해시만_지운다_수동_실행_이름도_등록된다() {
        long rejoined = withdrawn("01011112222", "yrejoin@yamu.app", Duration.ofDays(31));
        long card = card(rejoined);
        jdbc.update("""
                UPDATE app_user SET phone_hash = NULL, birth_date = NULL, withdrawn_name_hash = NULL,
                    ssafy_member_id = NULL, ssafy_user_key = NULL WHERE user_id = ?""", rejoined);

        JobRunReport report = job.run(new JobRunCommand(now, null, false));

        assertThat(report.results()).extracting(JobRunReport.Item::userId).containsExactly(rejoined);
        assertThat(jdbc.queryForObject("SELECT issuer_account_hash FROM user_card WHERE user_card_id = ?",
                byte[].class, card)).isNull();
        assertThat(manualJobs).extracting(ManualJob::name).contains("withdrawn-key-purge");
    }

    // ───────── helpers ─────────

    /** 탈퇴 API가 남기는 모양: 이름·휴대폰 암호문·PIN은 NULL, 재가입 열쇠만 있음 */
    private long withdrawn(String phone, String memberId, Duration ago) {
        LocalDateTime deletedAt = LocalDateTime.ofInstant(now.minus(ago), ZoneOffset.UTC);
        Map<String, Object> values = new HashMap<>(Map.of(
                "phone_hash", lookupHasher.phone(phone), "birth_date", Date.valueOf(LocalDate.of(1999, 3, 15)),
                "withdrawn_name_hash", lookupHasher.name("김야무"), "ssafy_member_id", memberId,
                "ssafy_user_key", cipher.encrypt(UUID.randomUUID().toString(), CipherField.APP_USER_SSAFY_USER_KEY),
                "status", "WITHDRAWN", "deleted_at", deletedAt));
        values.put("created_at", deletedAt.minusDays(10));
        values.put("updated_at", deletedAt);
        return insert("app_user", "user_id", values);
    }

    private long pending(String phone) {
        LocalDateTime at = LocalDateTime.ofInstant(now.minus(Duration.ofDays(60)), ZoneOffset.UTC);
        return insert("app_user", "user_id", new HashMap<>(Map.of("name", "김야무",
                "birth_date", Date.valueOf(LocalDate.of(1999, 3, 15)),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE), "phone_hash", lookupHasher.phone(phone),
                "status", "PENDING", "created_at", at, "updated_at", at)));
    }

    private long card(long userId) {
        LocalDateTime at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        long product = insert("card_product", "card_product_id", new HashMap<>(Map.of("issuer_code", "SHINHAN",
                "product_name", "신한 " + UUID.randomUUID(), "card_type", "CREDIT", "billing_cutoff_days", 14,
                "created_at", at, "updated_at", at)));
        Map<String, Object> values = new HashMap<>(Map.of("user_id", userId, "card_product_id", product,
                "card_no_enc", cipher.encrypt(CARD_NO, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(CARD_NO), "card_last4", "0011", "status", "PENDING",
                "payment_day", 14, "issuer_account_bank_code", "088",
                "issuer_account_hash", lookupHasher.account("088", ACCOUNT_NO), "issuer_account_last4", "6547"));
        values.put("created_at", at);
        values.put("updated_at", at);
        return insert("user_card", "user_card_id", values);
    }

    private Map<String, Object> user(long userId) {
        return jdbc.queryForMap("SELECT * FROM app_user WHERE user_id = ?", userId);
    }

    private byte[] cardNoHash(long cardId) {
        return jdbc.queryForObject("SELECT card_no_hash FROM user_card WHERE user_card_id = ?", byte[].class, cardId);
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }
}
