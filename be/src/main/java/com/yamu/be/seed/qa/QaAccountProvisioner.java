package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaAccountPreparationException.require;
import static com.yamu.be.seed.qa.QaAccountPreparationException.Code.*;

import com.yamu.be.auth.PinPolicy;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PinHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Explicit QA-only database operation. Does not start Spring, issue tokens, or call a bank. */
public final class QaAccountProvisioner {
    private static final Map<String, String> ALLOWED = Map.of(
            "qa-550-life-1", "최인국", "qa-550-life-2", "남윤주", "qa-550-life-3", "박준수");
    private static final String EVENT_PREFIX = "QA550_OFFLINE_PREP:";
    private final Connection connection;
    private final JdbcTemplate db;
    private final Clock clock;
    private final LookupHasher lookup;
    private final PersonalDataCipher cipher;
    private final PinHasher pins;
    private final QaSeedService seed;

    public QaAccountProvisioner(Connection connection, CryptoKeys keys, Clock clock, String hmacKey) {
        this.connection = connection;
        this.db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        this.clock = clock;
        this.lookup = new LookupHasher(keys);
        this.cipher = new PersonalDataCipher(keys, new SecureRandom());
        this.pins = new PinHasher(keys);
        this.seed = new QaSeedService(connection, keys, clock, hmacKey);
    }

    public record Target(String datasetKey, long userId, String name, Long userDeviceId, String deviceId,
                         String publicKeyFingerprint, List<Long> linkedAccountIds, List<Long> cardIds,
                         boolean prepared) {}

    public record Result(int version, String mode, String manifestDigest, List<Target> targets) {}

    /** The three targets are checked before any mutation and prepared in one transaction. */
    public Result run(String mode, List<QaSeedSpec> specs, QaAccountDevices.Manifest manifest,
                      Map<String, String> pinInputs) throws Exception {
        require(List.of("plan", "prepare", "verify").contains(mode), INVALID_INPUT);
        boolean write = "prepare".equals(mode);
        validateSpecs(specs);
        manifest.validate(clock, false);
        if (write) {
            require(pinInputs != null && pinInputs.keySet().equals(QaAccountDevices.DATASETS), PIN_REQUIRED);
            for (String pin : pinInputs.values()) {
                try { PinPolicy.requireAllowed(pin); }
                catch (RuntimeException failure) { throw new QaAccountPreparationException(INVALID_PIN); }
            }
        }
        require(connection.getAutoCommit(), INVALID_INPUT);
        String database = db.queryForObject("SELECT DATABASE()", String.class);
        require(database != null, INVALID_INPUT);
        String lock = "qa442:" + UUID.nameUUIDFromBytes(database.getBytes(StandardCharsets.UTF_8));
        require(Integer.valueOf(1).equals(db.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, lock)), OPERATION_BUSY);
        try {
            db.execute("SET time_zone = '+00:00'");
            connection.setReadOnly(!write);
            connection.setAutoCommit(false);
            try {
                List<State> states = new ArrayList<>();
                for (QaSeedSpec spec : specs.stream().sorted(java.util.Comparator.comparing(QaSeedSpec::datasetKey)).toList()) {
                    State state = inspect(spec, manifest.forDataset(spec.datasetKey()), write);
                    if (!state.prepared) {
                        require(!"verify".equals(mode), NOT_PREPARED);
                        state.device.validate(clock, true);
                    } else if (write) {
                        require(pins.matches(pinInputs.get(spec.datasetKey()), (String) state.user.get("pin_hash")), STATE_CHANGED);
                    }
                    states.add(state);
                }
                if (write) for (State state : states) {
                    if (!state.prepared) prepare(state, pinInputs.get(state.spec.datasetKey()));
                }
                List<Target> targets = new ArrayList<>();
                for (State state : states) {
                    if (write) state = inspect(state.spec, state.device, true);
                    targets.add(target(state));
                }
                if (write) connection.commit(); else connection.rollback();
                return new Result(1, mode, manifest.digest(), List.copyOf(targets));
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
                connection.setReadOnly(false);
            }
        } finally {
            db.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock);
        }
    }

    private void validateSpecs(List<QaSeedSpec> specs) {
        require(specs != null && specs.size() == 3, TARGET_NOT_ALLOWED);
        var datasets = new HashSet<String>();
        var accounts = new HashSet<String>();
        for (QaSeedSpec spec : specs) {
            require(spec != null && ALLOWED.containsKey(spec.datasetKey()) && datasets.add(spec.datasetKey()), TARGET_NOT_ALLOWED);
            spec.validate(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
            require(spec.version() == 3 && spec.personas().size() == 1, TARGET_NOT_ALLOWED);
            var person = spec.personas().get(0);
            require(person.scenario() == QaSeedSpec.Scenario.LIFE && ALLOWED.get(spec.datasetKey()).equals(person.name())
                    && !person.ssafyUserKey().startsWith("REPLACE_"), TARGET_NOT_ALLOWED);
            for (var account : person.accounts()) {
                require(accounts.add(account.bankCode() + ":" + account.accountNo()), ACCOUNT_CONFLICT);
            }
        }
        require(datasets.equals(QaAccountDevices.DATASETS), TARGET_NOT_ALLOWED);
    }

    private State inspect(QaSeedSpec spec, QaAccountDevices.Device device, boolean locked) throws Exception {
        String suffix = locked ? " FOR UPDATE" : "";
        var registries = db.queryForList("SELECT * FROM qa_seed_dataset WHERE dataset_key=?" + suffix, spec.datasetKey());
        require(registries.size() == 1 && MessageDigest.isEqual(
                (byte[]) registries.get(0).get("input_hash"), seed.fingerprint(spec)), INPUT_CHANGED);
        var members = db.queryForList("SELECT * FROM qa_seed_member WHERE dataset_key=?" + suffix, spec.datasetKey());
        require(members.size() == 1, TARGET_NOT_ALLOWED);
        var person = spec.personas().get(0);
        long userId = ((Number) members.get(0).get("user_id")).longValue();
        require(userId == device.expectedUserId() && person.key().equals(members.get(0).get("persona_key")), TARGET_NOT_ALLOWED);
        var user = db.queryForMap("SELECT * FROM app_user WHERE user_id=?" + suffix, userId);
        require(person.name().equals(user.get("name")) && person.birthDate().toString().equals(user.get("birth_date").toString())
                && person.ssafyMemberId().equals(user.get("ssafy_member_id"))
                && MessageDigest.isEqual(lookup.phone(person.phone()), (byte[]) user.get("phone_hash"))
                && person.ssafyUserKey().equals(cipher.decrypt((byte[]) user.get("ssafy_user_key"), CipherField.APP_USER_SSAFY_USER_KEY)),
                INPUT_CHANGED);
        var cards = db.queryForList("SELECT * FROM user_card WHERE user_id=? ORDER BY user_card_id" + suffix, userId);
        var accounts = db.queryForList("SELECT * FROM linked_account WHERE user_id=? ORDER BY linked_account_id" + suffix, userId);
        var devices = db.queryForList("SELECT * FROM user_device WHERE user_id=? ORDER BY user_device_id" + suffix, userId);
        require(cards.size() == 4, STATE_CHANGED);
        require(count("SELECT COUNT(*) FROM user_device WHERE public_key=? AND user_id<>?", device.publicKey(), userId) == 0,
                DEVICE_CONFLICT);
        boolean prepared = "ACTIVE".equals(user.get("status"));
        if (prepared) {
            require(user.get("pin_hash") != null && user.get("identity_verified_at") != null && devices.size() == 1,
                    STATE_CHANGED);
            var registered = devices.get(0);
            require(device.deviceId().equals(registered.get("device_uuid"))
                    && device.publicKey().equals(registered.get("public_key"))
                    && "EC_P256".equals(registered.get("key_algorithm"))
                    && "ACTIVE".equals(registered.get("status"))
                    && count("SELECT COUNT(*) FROM user_device_event WHERE user_device_id=? AND event_type='REGISTERED' AND reason=?",
                        registered.get("user_device_id"), EVENT_PREFIX + device.bindingDigest()) == 1, DEVICE_CONFLICT);
            require(accounts.size() == 3, STATE_CHANGED);
        } else {
            require("PENDING".equals(user.get("status")) && user.get("pin_hash") == null && user.get("pin_updated_at") == null
                    && user.get("identity_verified_at") == null && devices.isEmpty() && accounts.isEmpty()
                    && !Boolean.TRUE.equals(members.get(0).get("enriched")), STATE_CHANGED);
        }
        int primary = 0;
        for (var expected : person.accounts()) {
            byte[] hash = lookup.account(expected.bankCode(), expected.accountNo());
            require(count("SELECT COUNT(*) FROM linked_account WHERE bank_code=? AND account_no_hash=? AND user_id<>?",
                    expected.bankCode(), hash, userId) == 0, ACCOUNT_CONFLICT);
            if (prepared) {
                var account = account(accounts, expected);
                require("CONNECTED".equals(account.get("status"))
                        && expected.accountNo().equals(cipher.decrypt((byte[]) account.get("account_no_enc"), CipherField.LINKED_ACCOUNT_NO)),
                        STATE_CHANGED);
                if (Boolean.TRUE.equals(account.get("is_primary"))) primary++;
            }
        }
        if (prepared) require(primary == 1, STATE_CHANGED);
        for (var expected : person.cards()) {
            byte[] cardHash = lookup.card(expected.cardNo());
            var matches = cards.stream().filter(row -> Arrays.equals(cardHash, (byte[]) row.get("card_no_hash"))).toList();
            require(matches.size() == 1, INPUT_CHANGED);
            var card = matches.get(0);
            var expectedAccount = person.accounts().get(expected.accountIndex());
            require(expectedAccount.bankCode().equals(card.get("issuer_account_bank_code"))
                    && Arrays.equals(lookup.account(expectedAccount.bankCode(), expectedAccount.accountNo()),
                        (byte[]) card.get("issuer_account_hash"))
                    && count("SELECT COUNT(*) FROM card_product WHERE card_product_id=? AND issuer_code=? AND product_name=? AND card_type=?",
                        card.get("card_product_id"), expected.issuerCode(), expected.productName(), expected.cardType()) == 1,
                    INPUT_CHANGED);
            if (prepared) require(List.of("ACTIVE", "INACTIVE").contains(card.get("status"))
                    && card.get("registered_at") != null
                    && card.get("linked_account_id") != null
                    && card.get("linked_account_id").equals(account(accounts, expectedAccount).get("linked_account_id")), STATE_CHANGED);
            else require("PENDING".equals(card.get("status")) && card.get("registered_at") == null
                    && card.get("linked_account_id") == null, STATE_CHANGED);
        }
        return new State(spec, device, userId, user, cards, accounts, devices, prepared);
    }

    private Map<String, Object> account(List<Map<String, Object>> rows, QaSeedSpec.Account expected) {
        var matches = rows.stream().filter(row -> expected.bankCode().equals(row.get("bank_code"))
                && Arrays.equals(lookup.account(expected.bankCode(), expected.accountNo()), (byte[]) row.get("account_no_hash"))).toList();
        require(matches.size() == 1, STATE_CHANGED);
        return matches.get(0);
    }

    private void prepare(State state, String pin) throws Exception {
        Timestamp now = Timestamp.from(clock.instant());
        var person = state.spec.personas().get(0);
        Map<String, Long> ids = new HashMap<>();
        for (int i = 0; i < person.accounts().size(); i++) {
            var account = person.accounts().get(i);
            long id = seed.insert("""
                    INSERT INTO linked_account(user_id,bank_code,account_no_enc,account_no_hash,account_last4,
                        status,is_primary,connected_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,'CONNECTED',?,?,?,?)
                    """, state.userId, account.bankCode(), cipher.encrypt(account.accountNo(), CipherField.LINKED_ACCOUNT_NO),
                    lookup.account(account.bankCode(), account.accountNo()), account.accountNo().substring(account.accountNo().length()-4),
                    i == 0, now, now, now);
            ids.put(account.bankCode(), id);
        }
        for (var card : state.cards) {
            require(db.update("""
                    UPDATE user_card SET status='ACTIVE',linked_account_id=?,registered_at=?,updated_at=?,version=version+1
                    WHERE user_card_id=? AND user_id=? AND status='PENDING' AND linked_account_id IS NULL
                    """, ids.get(card.get("issuer_account_bank_code")), now, now, card.get("user_card_id"), state.userId) == 1,
                    STATE_CHANGED);
        }
        long deviceId = seed.insert("""
                INSERT INTO user_device(user_id,device_uuid,device_name,platform,public_key,key_algorithm,
                    biometric_enabled,status,failed_count,created_at,updated_at)
                VALUES (?,?,?,'ANDROID',?,'EC_P256',FALSE,'ACTIVE',0,?,?)
                """, state.userId, state.device.deviceId(), state.device.deviceName(), state.device.publicKey(), now, now);
        seed.insert("""
                INSERT INTO user_device_event(user_device_id,event_type,reason,created_at) VALUES (?,'REGISTERED',?,?)
                """, deviceId, EVENT_PREFIX + state.device.bindingDigest(), now);
        require(db.update("""
                UPDATE app_user SET pin_hash=?,pin_updated_at=?,identity_verified_at=?,status='ACTIVE',
                    updated_at=?,version=version+1 WHERE user_id=? AND status='PENDING' AND pin_hash IS NULL
                """, pins.hash(pin), now, now, now, state.userId) == 1, STATE_CHANGED);
    }

    private Target target(State state) {
        Long deviceId = state.devices.isEmpty() ? null : ((Number) state.devices.get(0).get("user_device_id")).longValue();
        return new Target(state.spec.datasetKey(), state.userId, state.spec.personas().get(0).name(), deviceId,
                state.device.deviceId(), state.device.fingerprint(),
                state.accounts.stream().map(row -> ((Number) row.get("linked_account_id")).longValue()).toList(),
                state.cards.stream().map(row -> ((Number) row.get("user_card_id")).longValue()).toList(), state.prepared);
    }

    private long count(String sql, Object... values) {
        return db.queryForObject(sql, Long.class, values);
    }

    private record State(QaSeedSpec spec, QaAccountDevices.Device device, long userId,
                         Map<String, Object> user, List<Map<String, Object>> cards,
                         List<Map<String, Object>> accounts, List<Map<String, Object>> devices, boolean prepared) {
        @Override public String toString() { return "QaAccountState[REDACTED]"; }
    }
}
