package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaAccountDeviceFixtures.*;
import static com.yamu.be.seed.qa.QaAccountPreparationException.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.auth.AuthService;
import com.yamu.be.auth.ChallengeStore;
import com.yamu.be.auth.ClientInfo;
import com.yamu.be.auth.DeviceCredentialProcessor.CredentialCommand;
import com.yamu.be.auth.LoginRateLimiter;
import com.yamu.be.auth.RefreshTokenStore;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.CryptoProperties;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QaAccountProvisionerTest extends MySqlTestSupport {
    @Autowired DataSource dataSource;
    @Autowired CryptoProperties crypto;
    @Autowired AuthService auth;
    @MockitoBean FinApiClient financial;
    @MockitoBean SsafyTransferApi transfers;
    @MockitoBean ChallengeStore challenges;
    @MockitoBean RefreshTokenStore refresh;
    @MockitoBean LoginRateLimiter rateLimiter;
    @MockitoBean Clock runtimeClock;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<QaSeedSpec> specs = new ArrayList<>();
    private final Map<String, KeyPair> keyPairs = new HashMap<>();
    private final Map<String, String> pins = Map.of("qa-550-life-1", "382947", "qa-550-life-2", "495827", "qa-550-life-3", "596238");
    private Connection connection;
    private JdbcTemplate db;
    private QaSeedService seed;
    private QaAccountProvisioner tool;
    private QaAccountDevices.Manifest manifest;

    @BeforeEach void setup() throws Exception {
        when(runtimeClock.instant()).thenReturn(NOW);
        when(runtimeClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(runtimeClock.withZone(any())).thenAnswer(call -> Clock.fixed(NOW, call.getArgument(0)));
        connection = dataSource.getConnection();
        db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        var keys = new CryptoKeys(crypto);
        seed = new QaSeedService(connection, keys, clock, crypto.hmacKey());
        tool = new QaAccountProvisioner(connection, keys, clock, crypto.hmacKey());
        for (String file : List.of("01-card-catalog.sql", "02-card-catalog-ab20.sql", "05-ibk-narasarang.sql"))
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("seed", file)));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var devices = new ArrayList<QaAccountDevices.Device>();
        for (int n = 1; n <= 3; n++) {
            var input = mapper.readValue(Path.of("seed/qa/life-persona-" + n + ".example.json").toFile(), QaSeedSpec.class);
            var p = input.personas().get(0);
            var person = new QaSeedSpec.Persona(p.key(), p.scenario(), p.name(), p.birthDate(), p.phone(),
                    p.ssafyMemberId(), "isolated-preparation-" + n, p.accounts(), p.cards(), p.issuanceEligibility());
            var spec = new QaSeedSpec(input.datasetKey(), 3, input.baseDate(), List.of(person));
            seed.run("apply", spec);
            specs.add(spec);
            var pair = key();
            keyPairs.put(spec.datasetKey(), pair);
            devices.add(device(n, user(spec), pair, NOW));
        }
        manifest = new QaAccountDevices.Manifest(1, devices);
    }

    @AfterEach void cleanup() throws Exception {
        try {
            for (var spec : specs) seed.run("purge", spec);
            verifyNoInteractions(financial, transfers);
        } finally { connection.close(); specs.clear(); keyPairs.clear(); }
    }

    @Test void preparesAllThreePreservesHistoryAndSupportsNormalSignedPinLoginThenEnrich() throws Exception {
        var before = history();
        var benefits = snapshot("SELECT * FROM payment_benefit ORDER BY payment_benefit_id");
        assertThat(tool.run("plan", specs, manifest, Map.of()).targets()).allMatch(t -> !t.prepared());
        assertThat(count("linked_account")).isZero();
        var result = tool.run("prepare", specs, manifest, pins);
        assertThat(result.targets()).hasSize(3).allMatch(QaAccountProvisioner.Target::prepared);
        assertThat(count("linked_account")).isEqualTo(9);
        assertThat(count("user_device")).isEqualTo(3);
        assertThat(count("user_device_event")).isEqualTo(3);
        assertThat(count("step_up_audit_log")).isZero();
        assertThat(history()).isEqualTo(before);
        assertThat(snapshot("SELECT * FROM payment_benefit ORDER BY payment_benefit_id")).isEqualTo(benefits);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_card WHERE status='ACTIVE' AND linked_account_id IS NOT NULL", Long.class)).isEqualTo(12);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM linked_account WHERE is_primary=TRUE", Long.class)).isEqualTo(3);
        var pinHashes = db.queryForList("SELECT user_id,pin_hash FROM app_user ORDER BY user_id");
        for (var target : result.targets()) {
            long id = target.userDeviceId();
            String challenge = "ordinary-login-challenge-" + id;
            when(challenges.consume("ch-" + id)).thenReturn(Optional.of(new ChallengeStore.StoredChallenge(id, StepUpPurpose.LOGIN, challenge)));
            when(refresh.issue(id)).thenReturn("isolated-refresh");
            var login = auth.login(new CredentialCommand(id, "ch-" + id, AuthMethod.PIN,
                    sign(keyPairs.get(target.datasetKey()), challenge), pins.get(target.datasetKey())), new ClientInfo("127.0.0.1", "JUnit"));
            assertThat(login.user().userId()).isEqualTo(target.userId());
            assertThat(login.user().onboardingCompleted()).isTrue();
        }
        for (var spec : specs) {
            seed.run("enrich", spec);
            seed.run("verify", spec);
            assertThat(db.queryForObject("SELECT SUM(confirmed_amount) FROM card_bill WHERE user_id=? AND billing_month='2026-11-01'", Long.class, user(spec))).isEqualTo(896500);
            assertThat(db.queryForObject("SELECT SUM(i.billed_amount) FROM bill_item i JOIN card_bill b USING(card_bill_id) WHERE b.user_id=? AND b.billing_month='2026-12-01'", Long.class, user(spec))).isEqualTo(420000);
        }
        var enriched = history();
        var repeat = tool.run("prepare", specs, manifest, pins);
        assertThat(repeat.targets()).isEqualTo(result.targets());
        assertThat(history()).isEqualTo(enriched);
        assertThat(db.queryForList("SELECT user_id,pin_hash FROM app_user ORDER BY user_id")).isEqualTo(pinHashes);
        assertThat(tool.run("verify", specs, manifest, Map.of()).targets()).isEqualTo(result.targets());
        assertThat(count("qa_seed_funding_boundary")).isZero();
    }

    @Test void wrongTargetOrChangedSourceRejectsBeforeAnyWrite() throws Exception {
        var before = history();
        var altered = new ArrayList<>(manifest.devices());
        altered.set(1, device(2, user(specs.get(1)) + 100, keyPairs.get(specs.get(1).datasetKey()), NOW));
        assertThatThrownBy(() -> tool.run("prepare", specs, new QaAccountDevices.Manifest(1, altered), pins))
                .hasMessage(TARGET_NOT_ALLOWED.name());
        db.update("UPDATE app_user SET name='변경된 이름' WHERE user_id=?", user(specs.get(2)));
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(INPUT_CHANGED.name());
        assertThat(count("linked_account")).isZero();
        assertThat(count("user_device")).isZero();
        assertThat(history()).isEqualTo(before);
    }

    @Test void doesNotOverwritePartiallyRegisteredAccountOrExistingPin() throws Exception {
        db.update("UPDATE app_user SET pin_hash='existing-hash' WHERE user_id=?", user(specs.get(2)));
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(STATE_CHANGED.name());
        assertThat(db.queryForObject("SELECT pin_hash FROM app_user WHERE user_id=?", String.class, user(specs.get(2)))).isEqualTo("existing-hash");
        assertThat(count("linked_account")).isZero();
        assertThat(count("user_device")).isZero();
    }

    @Test void transactionFailureOnSecondDeviceRollsBackFirstUserToo() throws Exception {
        var before = history();
        long second = user(specs.get(1));
        // A temporary constraint in this Testcontainers database forces a genuine SQL failure after the first user.
        db.execute("ALTER TABLE user_device ADD CONSTRAINT reject_second_qa_device CHECK(user_id<>" + second + ")");
        try {
            assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).isInstanceOf(java.sql.SQLException.class);
        } finally { db.execute("ALTER TABLE user_device DROP CHECK reject_second_qa_device"); }
        assertThat(count("linked_account")).isZero();
        assertThat(count("user_device")).isZero();
        assertThat(count("user_device_event")).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE status='PENDING' AND pin_hash IS NULL", Long.class)).isEqualTo(3);
        assertThat(history()).isEqualTo(before);
    }

    @Test void incorrectPinAndDifferentDeviceCannotResetPreparedCredentials() throws Exception {
        tool.run("prepare", specs, manifest, pins);
        var before = snapshot("SELECT * FROM app_user ORDER BY user_id");
        var wrongPins = new HashMap<>(pins);
        wrongPins.put("qa-550-life-1", "847295");
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, wrongPins)).hasMessage(STATE_CHANGED.name());
        var devices = new ArrayList<>(manifest.devices());
        devices.set(0, device(1, user(specs.get(0)), key(), NOW));
        assertThatThrownBy(() -> tool.run("prepare", specs, new QaAccountDevices.Manifest(1, devices), pins)).hasMessage(DEVICE_CONFLICT.name());
        assertThat(snapshot("SELECT * FROM app_user ORDER BY user_id")).isEqualTo(before);
        assertThat(count("user_device")).isEqualTo(3);
    }

    @Test void refusesAccountsOwnedByAnotherUserAndInvalidPinBeforeAnyWrite() throws Exception {
        var expected = specs.get(0).personas().get(0).accounts().get(0);
        var hash = new LookupHasher(new CryptoKeys(crypto)).account(expected.bankCode(), expected.accountNo());
        db.update("INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,account_last4,status,is_primary,connected_at,created_at,updated_at) VALUES (?,?,?,?,'0000','CONNECTED',TRUE,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                user(specs.get(2)), expected.bankCode(), new byte[]{1}, hash);
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(ACCOUNT_CONFLICT.name());
        var invalid = new HashMap<>(pins);
        invalid.put("qa-550-life-2", "111111");
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, invalid)).hasMessage(INVALID_PIN.name());
        assertThat(count("linked_account")).isEqualTo(1);
        assertThat(count("user_device")).isZero();
    }

    @Test void unpreparedVerifyAndConcurrentSeedOperationMakeNoChanges() throws Exception {
        assertThatThrownBy(() -> tool.run("verify", specs, manifest, Map.of())).hasMessage(NOT_PREPARED.name());
        String database = db.queryForObject("SELECT DATABASE()", String.class);
        String lock = "qa442:" + java.util.UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var other = dataSource.getConnection()) {
            var otherDb = new JdbcTemplate(new SingleConnectionDataSource(other, true));
            assertThat(otherDb.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, lock)).isEqualTo(1);
            try { assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(OPERATION_BUSY.name()); }
            finally { otherDb.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock); }
        }
        assertThat(count("linked_account")).isZero();
    }

    @Test void preservesWithdrawalAuditAndReusesOnlyVerifiedDisconnectedAccount() throws Exception {
        var old = addWithdrawalAudit();
        var auditAccount = snapshot("SELECT * FROM linked_account WHERE linked_account_id=" + old[1]);
        var devicesBefore = snapshot("SELECT * FROM user_device ORDER BY user_device_id");
        var eventsBefore = snapshot("SELECT * FROM user_device_event ORDER BY user_device_event_id");
        var purchases = history();
        var benefits = snapshot("SELECT * FROM payment_benefit ORDER BY payment_benefit_id");
        var performance = snapshot("SELECT * FROM card_monthly_performance ORDER BY card_monthly_performance_id");
        var plan = tool.run("plan", specs, manifest, Map.of());
        assertThat(plan.targets().get(0).userDeviceId()).isNull();
        assertThat(count("linked_account")).isEqualTo(2);
        var result = tool.run("prepare", specs, manifest, pins);
        assertThat(result.targets().get(0).linkedAccountIds()).contains(old[0]).doesNotContain(old[1]);
        assertThat(count("linked_account")).isEqualTo(10); // nine live accounts plus the wiped audit row
        assertThat(count("user_device")).isEqualTo(4); // three new devices plus the revoked audit row
        assertThat(db.queryForObject("SELECT COUNT(*) FROM linked_account WHERE status='CONNECTED'", Long.class)).isEqualTo(9);
        assertThat(snapshot("SELECT * FROM linked_account WHERE linked_account_id=" + old[1])).isEqualTo(auditAccount);
        assertThat(snapshot("SELECT * FROM user_device WHERE status='REVOKED' ORDER BY user_device_id")).isEqualTo(devicesBefore);
        assertThat(snapshot("SELECT * FROM user_device_event WHERE user_device_id=" + old[2] + " ORDER BY user_device_event_id")).isEqualTo(eventsBefore);
        assertThat(history()).isEqualTo(purchases);
        assertThat(snapshot("SELECT * FROM payment_benefit ORDER BY payment_benefit_id")).isEqualTo(benefits);
        assertThat(snapshot("SELECT * FROM card_monthly_performance ORDER BY card_monthly_performance_id")).isEqualTo(performance);
        assertThat(tool.run("prepare", specs, manifest, pins).targets()).isEqualTo(result.targets());
        assertThat(tool.run("verify", specs, manifest, Map.of()).targets()).isEqualTo(result.targets());
        for (var spec : specs) { seed.run("enrich", spec); seed.run("verify", spec); }
        assertThat(tool.run("verify", specs, manifest, Map.of()).targets()).isEqualTo(result.targets());
    }

    @Test void neverReusesRevokedDeviceIdentityOrAcceptsActivePartialOnboarding() throws Exception {
        var old = addWithdrawalAudit();
        var proof = manifest.devices().get(0);
        db.update("UPDATE user_device SET device_uuid=? WHERE user_device_id=?", proof.deviceId(), old[2]);
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(DEVICE_CONFLICT.name());
        db.update("UPDATE user_device SET device_uuid=?,public_key=? WHERE user_device_id=?", java.util.UUID.randomUUID().toString(), proof.publicKey(), old[2]);
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(DEVICE_CONFLICT.name());
        db.update("UPDATE user_device SET public_key='withdrawn:audit',status='ACTIVE' WHERE user_device_id=?", old[2]);
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(STATE_CHANGED.name());
        db.update("UPDATE user_device SET status='REVOKED' WHERE user_device_id=?", old[2]);
        db.update("UPDATE linked_account SET status='CONNECTED' WHERE linked_account_id=?", old[0]);
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(STATE_CHANGED.name());
        assertThat(count("linked_account")).isEqualTo(2);
        assertThat(count("user_device")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE status='PENDING'", Long.class)).isEqualTo(3);
    }

    @Test void disconnectedLookupHashAloneCannotAuthorizeReuse() throws Exception {
        var old = addWithdrawalAudit();
        var cipher = new com.yamu.be.global.crypto.PersonalDataCipher(new CryptoKeys(crypto), new java.security.SecureRandom());
        db.update("UPDATE linked_account SET account_no_enc=? WHERE linked_account_id=?",
                cipher.encrypt("999999999999", com.yamu.be.global.crypto.CipherField.LINKED_ACCOUNT_NO), old[0]);
        var before = snapshot("SELECT * FROM linked_account ORDER BY linked_account_id");
        assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).hasMessage(STATE_CHANGED.name());
        assertThat(snapshot("SELECT * FROM linked_account ORDER BY linked_account_id")).isEqualTo(before);
        assertThat(count("user_device")).isEqualTo(1);
    }

    @Test void failureAfterReconnectRollsBackAuditAndAllThreeUsers() throws Exception {
        addWithdrawalAudit();
        var accounts = snapshot("SELECT * FROM linked_account ORDER BY linked_account_id");
        var devices = snapshot("SELECT * FROM user_device ORDER BY user_device_id");
        var users = snapshot("SELECT * FROM app_user ORDER BY user_id");
        var cards = snapshot("SELECT * FROM user_card ORDER BY user_card_id");
        db.execute("ALTER TABLE user_device ADD CONSTRAINT reject_second_qa_device CHECK(user_id<>" + user(specs.get(1)) + ")");
        try { assertThatThrownBy(() -> tool.run("prepare", specs, manifest, pins)).isInstanceOf(java.sql.SQLException.class); }
        finally { db.execute("ALTER TABLE user_device DROP CHECK reject_second_qa_device"); }
        assertThat(snapshot("SELECT * FROM linked_account ORDER BY linked_account_id")).isEqualTo(accounts);
        assertThat(snapshot("SELECT * FROM user_device ORDER BY user_device_id")).isEqualTo(devices);
        assertThat(snapshot("SELECT * FROM app_user ORDER BY user_id")).isEqualTo(users);
        assertThat(snapshot("SELECT * FROM user_card ORDER BY user_card_id")).isEqualTo(cards);
    }

    private long[] addWithdrawalAudit() throws Exception {
        long user = user(specs.get(0));
        var expected = specs.get(0).personas().get(0).accounts().get(0);
        var keys = new CryptoKeys(crypto);
        var cipher = new com.yamu.be.global.crypto.PersonalDataCipher(keys, new java.security.SecureRandom());
        long reusable = seed.insert("""
                INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,account_last4,status,is_primary,
                  connected_at,disconnected_at,created_at,updated_at)
                VALUES (?,?,?,?,'0001','DISCONNECTED',FALSE,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())
                """, user, expected.bankCode(), cipher.encrypt(expected.accountNo(), com.yamu.be.global.crypto.CipherField.LINKED_ACCOUNT_NO),
                new LookupHasher(keys).account(expected.bankCode(), expected.accountNo()));
        long wiped = seed.insert("""
                INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,account_last4,status,is_primary,
                  connected_at,disconnected_at,created_at,updated_at)
                VALUES (?,'003',?,?,'0003','DISCONNECTED',FALSE,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())
                """, user, cipher.encrypt("withdrawn-random", com.yamu.be.global.crypto.CipherField.LINKED_ACCOUNT_NO), new byte[32]);
        long revoked = seed.insert("""
                INSERT INTO user_device(user_id,device_uuid,public_key,key_algorithm,platform,status,created_at,updated_at)
                VALUES (?,?,'withdrawn:audit','EC_P256','ANDROID','REVOKED',UTC_TIMESTAMP(),UTC_TIMESTAMP())
                """, user, java.util.UUID.randomUUID().toString());
        seed.insert("INSERT INTO user_device_event(user_device_id,event_type,reason,created_at) VALUES (?,'REVOKED','withdrawal audit',UTC_TIMESTAMP())", revoked);
        return new long[]{reusable,wiped,revoked};
    }

    private long user(QaSeedSpec spec) { return db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=?", Long.class, spec.datasetKey()); }
    private long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private List<Map<String, Object>> history() { return snapshot("SELECT * FROM payment_transaction ORDER BY payment_transaction_id"); }
    private List<Map<String, Object>> snapshot(String sql) {
        var rows = db.queryForList(sql);
        rows.forEach(row -> row.replaceAll((name, value) -> value instanceof byte[] bytes
                ? java.util.Base64.getEncoder().encodeToString(bytes) : value));
        return rows;
    }
}
