package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.CryptoKeys;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static com.yamu.be.seed.qa.QaSeedSpec.require;

/** One explicit transaction per dataset; unique registry ownership gates every mutation. */
public final class QaSeedService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Connection connection;
    private final JdbcTemplate db;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookup;
    private final Clock clock;
    private final byte[] registryKey;

    public QaSeedService(Connection connection, CryptoKeys keys, Clock clock, String hmacKey) {
        this.connection = connection;
        this.db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        this.cipher = new PersonalDataCipher(keys, new SecureRandom());
        this.lookup = new LookupHasher(keys);
        this.clock = clock;
        this.registryKey = Base64.getDecoder().decode(hmacKey);
    }

    public void run(String command, QaSeedSpec spec) throws Exception {
        spec.validate(LocalDate.now(clock.withZone(KST)));
        require(List.of("apply", "enrich", "verify", "inspect", "reset", "purge").contains(command), "Unknown operation");
        require(!List.of("apply", "enrich", "reset").contains(command) || (spec.version() == 2 || spec.version() == 3),
                "Use version 2 or 3 for executable fixtures; purge version 1 using its original input first");
        for (var persona : spec.personas()) {
            require(!persona.ssafyUserKey().startsWith("REPLACE_"), "Replace financial binding placeholders");
        }
        require(connection.getAutoCommit(), "Dedicated connection required");
        String database = db.queryForObject("SELECT DATABASE()", String.class);
        require(database != null, "Database required");
        String lock = "qa442:" + UUID.nameUUIDFromBytes(database.getBytes(StandardCharsets.UTF_8));
        require(Integer.valueOf(1).equals(db.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, lock)),
                "Another QA seed process is running");
        try {
            db.execute("SET time_zone = '+00:00'");
            connection.setAutoCommit(false);
            try {
                List<Map<String, Object>> existing = db.queryForList(
                        "SELECT * FROM qa_seed_dataset WHERE dataset_key=? FOR UPDATE", spec.datasetKey());
                byte[] hash = fingerprint(spec);
                if (!existing.isEmpty()) {
                    // Changed credentials, date, persona list, or version cannot silently select other users.
                    require(Arrays.equals(hash, (byte[]) existing.get(0).get("input_hash")),
                            "Input changed; purge with the original input first");
                }
                switch (command) {
                    case "apply" -> {
                        if (existing.isEmpty()) apply(spec, hash);
                        else System.out.println("Existing dataset preserved");
                    }
                    case "reset" -> {
                        require(!existing.isEmpty(), "Dataset not found");
                        remove(spec);
                        apply(spec, hash);
                    }
                    case "purge" -> {
                        if (!existing.isEmpty()) remove(spec);
                    }
                    case "enrich" -> {
                        require(!existing.isEmpty(), "Apply first");
                        enrich(spec);
                    }
                    case "inspect" -> {
                        require(!existing.isEmpty(), "Dataset not found");
                        inspect(spec);
                    }
                    case "verify" -> {
                        require(!existing.isEmpty(), "Dataset not found");
                        verify(spec);
                    }
                    default -> throw new IllegalArgumentException("Unknown operation");
                }
                if (List.of("verify", "inspect").contains(command)) connection.rollback();
                else connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            db.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock);
        }
    }

    private byte[] fingerprint(QaSeedSpec spec) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(registryKey, "HmacSHA256"));
        mac.update("YAMU-QA-442-V1:".getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(spec));
    }

    private void apply(QaSeedSpec spec, byte[] hash) throws Exception {
        // Resolving all dependencies precedes any write; no catalog creation or alteration.
        for (var p : spec.personas()) {
            for (var card : p.cards()) {
                product(card);
                require(count("SELECT COUNT(*) FROM user_card WHERE card_no_hash=?", lookup.card(card.cardNo())) == 0,
                        "Mock card already belongs to another user");
            }
            for (var payment : QaHistoryPlan.build(p.scenario(), spec.baseDate())) merchant(payment.brand());
            if (p.scenario() != QaSeedSpec.Scenario.EMPTY) utilityPolicy(product(p.cards().get(2)));
            require(count("SELECT COUNT(*) FROM app_user WHERE (phone_hash=? AND birth_date=?) OR ssafy_member_id=?",
                    lookup.phone(p.phone()), p.birthDate(), p.ssafyMemberId()) == 0, "Identity already belongs to another dataset/user");
            for (var a : p.accounts()) {
                require(count("SELECT COUNT(*) FROM linked_account WHERE bank_code=? AND account_no_hash=?",
                        a.bankCode(), lookup.account(a.bankCode(), a.accountNo())) == 0, "Account already linked");
            }
        }
        db.update("INSERT INTO qa_seed_dataset VALUES (?,?,?,?,?,?)", spec.datasetKey(), spec.version(),
                spec.baseDate(), hash, UUID.randomUUID().toString(), now());
        for (var p : spec.personas()) {
            long user = insert("""
                    INSERT INTO app_user(name,birth_date,phone_enc,phone_hash,ssafy_member_id,ssafy_user_key,
                      status,created_at,updated_at) VALUES (?,?,?,?,?,?,'PENDING',?,?)
                    """, p.name(), p.birthDate(), cipher.encrypt(com.yamu.be.global.crypto.PersonalDataNormalizer.phone(p.phone()), CipherField.APP_USER_PHONE),
                    lookup.phone(p.phone()), p.ssafyMemberId(),
                    cipher.encrypt(p.ssafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY), now(), now());
            db.update("INSERT INTO qa_seed_member(dataset_key,persona_key,user_id) VALUES (?,?,?)",
                    spec.datasetKey(), p.key(), user);
            long[] cards = new long[p.cards().size()], products = new long[p.cards().size()];
            for (int i = 0; i < p.cards().size(); i++) {
                var c = p.cards().get(i);
                var a = p.accounts().get(c.accountIndex());
                products[i] = product(c);
                cards[i] = insert("""
                        INSERT INTO user_card(user_id,card_product_id,card_no_enc,card_no_hash,card_last4,
                          issuer_account_bank_code,issuer_account_hash,issuer_account_last4,payment_day,
                          status,issuer_status,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,'PENDING','NORMAL',?,?)
                        """, user, products[i], cipher.encrypt(c.cardNo(), CipherField.USER_CARD_NO),
                        lookup.card(c.cardNo()), last4(c.cardNo()), a.bankCode(),
                        lookup.account(a.bankCode(), a.accountNo()), last4(a.accountNo()), c.paymentDay(), now(), now());
            }
            if (spec.version() == 3) {
                var ibk = p.cards().get(3);
                db.update("UPDATE user_card SET issuer_registered_on=?,ibk_context_month=?,ibk_salary_qualified=?,ibk_military_qualified=? WHERE user_card_id=? AND user_id=?",
                        ibk.issuerRegisteredOn(),spec.baseDate().withDayOfMonth(1),ibk.ibkSalaryQualified(),ibk.ibkMilitaryQualified(),cards[3],user);
            }
            for (var row : QaHistoryPlan.build(p.scenario(), spec.baseDate())) {
                long payment = payment(spec, p, user, cards[row.card()], products[row.card()], row);
                if (row.utilityDiscount() > 0) benefit(payment, products[row.card()], row);
            }
            aggregate(cards, products, spec.baseDate());
            System.out.printf("%s prepared payments=%d%n", p.key(),
                    QaHistoryPlan.build(p.scenario(), spec.baseDate()).size());
        }
    }

    private long product(QaSeedSpec.Card card) {
        return id("SELECT card_product_id FROM card_product WHERE issuer_code=? AND product_name=? AND card_type=?",
                card.issuerCode(), card.productName(), card.cardType());
    }
    private long merchant(String brand) {
        return id("SELECT merchant_id FROM merchant WHERE brand_code=?", brand);
    }
    private long utilityPolicy(long product) {
        long benefit = id("""
                SELECT card_benefit_id FROM card_benefit WHERE card_product_id=? AND benefit_code='UTILITY'
                AND policy_version=1 AND rate=0.10 AND value_unit='KRW' AND min_performance_amount=300000
                AND application_timing='AT_BILLING'
                """, product);
        require(count("""
                SELECT COUNT(*) FROM benefit_limit_link l JOIN limit_group g USING(limit_group_id)
                WHERE l.card_benefit_id=? AND g.policy_version=1
                """, benefit) == 3, "Utility fixture requires the three documented limits");
        require(count("""
                SELECT COUNT(*) FROM tier_limit t JOIN limit_group g USING(limit_group_id)
                WHERE g.card_product_id=? AND g.group_code='UTILITY_MONTHLY' AND g.policy_version=1
                AND t.min_performance_amount=300000 AND t.max_performance_amount=500000 AND t.limit_value=3000
                """, product) == 1, "Utility tier differs from fixture");
        return benefit;
    }

    private long payment(QaSeedSpec spec, QaSeedSpec.Persona persona, long user, long card, long product,
                         QaHistoryPlan.Payment row) throws Exception {
        String key = UUID.nameUUIDFromBytes((spec.datasetKey() + ":" + persona.key() + ":" + row.card()
                + ":" + row.date() + ":" + row.slot()).getBytes(StandardCharsets.UTF_8)).toString();
        boolean declined = "DECLINED".equals(row.status());
        boolean canceled = "CANCELED".equals(row.status());
        Timestamp at = Timestamp.from(row.date().atStartOfDay(KST).plusMinutes(row.slot()).toInstant());
        // The current-month utility entry is at midnight, never after baseDate's start.
        if (row.utilityDiscount() > 0) at = Timestamp.from(row.date().atStartOfDay(KST).toInstant());
        String category = db.queryForObject("SELECT merchant_category_code FROM merchant WHERE brand_code=?",
                String.class, row.brand());
        String snapshot = new ObjectMapper().writeValueAsString(Map.of("brandCode", row.brand(),
                "merchantCategoryCode", category, "source", "QA_SEED_442", "transactionKind", "PURCHASE"));
        return insert("""
                INSERT INTO payment_transaction(user_id,user_card_id,card_product_id,merchant_id,payment_channel,
                  facility_type,merchant_context_snapshot,request_key,request_fingerprint,source,status,
                  requested_amount,approved_amount,installment_months,recognized_performance_amount,
                  performance_month,requested_at,approved_at,canceled_at,performance_reversed_at,failure_code,
                  created_at,updated_at)
                VALUES (?,?,?,?,'OFFLINE','GENERAL',?,?,?,'SEED',?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, user, card, product, merchant(row.brand()), snapshot, key,
                MessageDigest.getInstance("SHA-256").digest((key + ":" + row.amount()).getBytes(StandardCharsets.UTF_8)),
                row.status(), row.amount(), declined ? null : row.amount(), declined ? null : row.installments(),
                declined ? null : row.amount(), declined ? null : row.date().withDayOfMonth(1),
                at, declined ? null : at, canceled ? at : null, canceled ? at : null,
                declined ? "ISSUER_DECLINED" : null, now(), now());
    }

    private void benefit(long payment, long product, QaHistoryPlan.Payment row) {
        long benefit = utilityPolicy(product);
        db.update("""
                INSERT INTO payment_benefit(payment_transaction_id,card_product_id,card_benefit_id,
                  benefit_value,value_unit,krw_amount,created_at) VALUES (?,?,?,?,'KRW',?,?)
                """, payment, product, benefit, row.utilityDiscount(), row.utilityDiscount(), now());
        List<Map<String, Object>> groups = db.queryForList("""
                SELECT g.* FROM limit_group g JOIN benefit_limit_link l USING(limit_group_id)
                WHERE l.card_benefit_id=? ORDER BY g.limit_group_id
                """, benefit);
        for (var group : groups) {
            String code = (String) group.get("group_code");
            long maximum = switch (code) {
                case "UTILITY_MONTHLY" -> 3000;
                case "UTILITY_DAILY_COUNT" -> 1;
                case "UTILITY_PER_TRANSACTION" -> 5000;
                default -> throw new IllegalStateException("Unexpected utility limit");
            };
            long used = code.endsWith("_COUNT") ? 1 : row.utilityDiscount();
            db.update("""
                    INSERT INTO payment_limit_usage(payment_transaction_id,card_product_id,limit_group_id,
                      remaining_before,consumed_value,usage_date,usage_month,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, payment, product, group.get("limit_group_id"), maximum, used, row.date(),
                    row.date().withDayOfMonth(1), now(), now());
        }
    }

    private void aggregate(long[] cards, long[] products, LocalDate base) {
        for (int i = 0; i < cards.length; i++) {
            for (LocalDate month = base.withDayOfMonth(1).minusMonths(6);
                 !month.isAfter(base.withDayOfMonth(1)); month = month.plusMonths(1)) {
                db.update("""
                        INSERT INTO card_monthly_performance(user_card_id,performance_month,recognized_amount,
                          created_at,updated_at)
                        SELECT ?,?,COALESCE(SUM(recognized_performance_amount),0),?,?
                        FROM payment_transaction WHERE user_card_id=? AND performance_month=? AND status='APPROVED'
                        """, cards[i], month, now(), now(), cards[i], month);
            }
            db.update("""
                    INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,
                      used_value,created_at,updated_at)
                    SELECT ?,?,g.limit_group_id,?,COALESCE((
                        SELECT SUM(u.consumed_value) FROM payment_limit_usage u
                        JOIN payment_transaction t USING(payment_transaction_id)
                        WHERE t.user_card_id=? AND u.limit_group_id=g.limit_group_id AND u.usage_month=?
                        AND u.restored_at IS NULL),0),?,?
                    FROM limit_group g WHERE g.card_product_id=? AND g.period_type='MONTHLY'
                    """, cards[i], products[i], base.withDayOfMonth(1), cards[i], base.withDayOfMonth(1),
                    now(), now(), products[i]);
        }
    }

    private void enrich(QaSeedSpec spec) throws Exception {
        for (var p : spec.personas()) {
            long user = owner(spec, p);
            boolean enriched = Boolean.TRUE.equals(db.queryForObject(
                    "SELECT enriched FROM qa_seed_member WHERE user_id=? FOR UPDATE", Boolean.class, user));
            if (enriched) continue;
            require("ACTIVE".equals(db.queryForObject("SELECT status FROM app_user WHERE user_id=? FOR UPDATE",
                    String.class, user)), "Complete normal signup first");
            long[] accounts = new long[p.accounts().size()];
            for (int i = 0; i < p.accounts().size(); i++) {
                var a = p.accounts().get(i);
                accounts[i] = id("""
                        SELECT linked_account_id FROM linked_account WHERE user_id=? AND bank_code=?
                        AND account_no_hash=? AND status='CONNECTED'
                        """, user, a.bankCode(), lookup.account(a.bankCode(), a.accountNo()));
            }
            long[] cards = new long[p.cards().size()];
            for (int i = 0; i < p.cards().size(); i++) {
                var c = p.cards().get(i);
                cards[i] = id("""
                        SELECT user_card_id FROM user_card WHERE user_id=? AND card_no_hash=?
                        AND status='ACTIVE' AND linked_account_id=?
                        """, user, lookup.card(c.cardNo()), accounts[c.accountIndex()]);
            }
            for (int i = 0; i < cards.length; i++) {
                db.update("UPDATE user_card SET card_alias=?,display_order=?,updated_at=? WHERE user_card_id=?",
                        "QA " + p.key() + " " + (i == 0 ? "생활 체크" : i == 1 ? "처음" : i == 2 ? "Mr.Life" : "IBK나라사랑"),
                        i, now(), cards[i]);
            }
            if (p.scenario() != QaSeedSpec.Scenario.EMPTY) {
                long income = cashflow(user, accounts[0], spec.baseDate().plusDays(10), "QA 급여", "INCOME",
                        2500000L, "MONTHLY");
                cashflow(user, accounts[0], spec.baseDate().plusDays(2), "QA 월세", "EXPENSE", 700000L, "MONTHLY");
                cashflow(user, accounts[1], spec.baseDate().plusDays(5), "QA 정기 구독", "EXPENSE", 13500L, "MONTHLY");
                cashflow(user, accounts[0], spec.baseDate().plusDays(4), "QA 금액 미정 일정", "EXPENSE", null, "NONE");
                cashflow(user, accounts[0], spec.baseDate().plusDays(7),
                        "QA 긴 이름 표시 확인용 생활용품 및 주방용품 구매 예정", "EXPENSE", 90000L, "NONE");
                db.update("UPDATE app_user SET forecast_settings=?,updated_at=? WHERE user_id=?",
                        new ObjectMapper().writeValueAsString(Map.of("horizon", "DAYS", "defaultDays", 30,
                                "incomeCashflowItemId", income)), now(), user);
                // Seed only existing notification contract names; no push or external message delivery.
                for (int n = 0; n < 24; n++) {
                    db.update("""
                            INSERT INTO notification(user_id,notification_type,deduplication_key,title,body,
                              read_at,created_at,updated_at) VALUES (?,'PAYMENT_RESULT',?,?,?,?,?,?)
                            """, user, spec.datasetKey() + ":" + p.key() + ":" + n,
                            "QA 거래 내역 확인 " + (n + 1), "화면 표시 확인용 알림입니다.",
                            n % 2 == 0 ? null : now(), now(), now());
                }
                new QaBillingFixtures(db, this::insert, clock).create(user, spec.baseDate());
            }
            db.update("UPDATE qa_seed_member SET enriched=TRUE WHERE user_id=?", user);
        }
    }

    private long cashflow(long user, long account, LocalDate date, String title, String direction,
                          Long amount, String recurrence) throws Exception {
        long item = insert("""
                INSERT INTO cashflow_item(user_id,linked_account_id,title,direction,amount,scheduled_date,
                  recurrence_type,recurrence_day_of_month,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'CONFIRMED',?,?)
                """, user, account, title, direction, amount, date, recurrence,
                "MONTHLY".equals(recurrence) ? date.getDayOfMonth() : null, now(), now());
        db.update("""
                INSERT INTO cashflow_occurrence(cashflow_item_id,user_id,occurrence_date,scheduled_date,
                  title,direction,amount,linked_account_id,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'CONFIRMED',?,?)
                """, item, user, date, date, title, direction, amount, account, now(), now());
        return item;
    }

    private long owner(QaSeedSpec spec, QaSeedSpec.Persona p) {
        return id("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key=?",
                spec.datasetKey(), p.key());
    }

    private void verify(QaSeedSpec spec) {
        require(count("SELECT COUNT(*) FROM qa_seed_member WHERE dataset_key=?", spec.datasetKey())
                == spec.personas().size(), "Registry membership mismatch");
        for (var p : spec.personas()) {
            long user = owner(spec, p);
            require(count("SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND source='SEED'", user)
                    == QaHistoryPlan.build(p.scenario(), spec.baseDate()).size(), "Initial history row count mismatch");
            require(count("SELECT COUNT(*) FROM card_monthly_performance m JOIN user_card c USING(user_card_id) WHERE c.user_id=? AND m.performance_month BETWEEN ? AND ?", user, spec.baseDate().withDayOfMonth(1).minusMonths(6), spec.baseDate().withDayOfMonth(1)) >= p.cards().size() * 7, "Missing performance months");
            require(count("""
                    SELECT COUNT(*) FROM card_monthly_performance m JOIN user_card c USING(user_card_id)
                    WHERE c.user_id=? AND m.recognized_amount<>(
                      SELECT COALESCE(SUM(t.recognized_performance_amount),0) FROM payment_transaction t
                      WHERE t.user_card_id=m.user_card_id AND t.performance_month=m.performance_month
                      AND t.status='APPROVED')
                    """, user) == 0, "Performance totals mismatch");
            require(count("""
                    SELECT COUNT(*) FROM monthly_limit_usage m JOIN user_card c USING(user_card_id)
                    WHERE c.user_id=? AND m.used_value<>(
                      SELECT COALESCE(SUM(u.consumed_value),0) FROM payment_limit_usage u
                      JOIN payment_transaction t USING(payment_transaction_id)
                      WHERE t.user_card_id=m.user_card_id AND u.limit_group_id=m.limit_group_id
                      AND u.usage_month=m.usage_month AND u.restored_at IS NULL)
                    """, user) == 0, "Limit totals mismatch");
            require(count("""
                    SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND source='SEED'
                    AND (fin_request_no IS NOT NULL OR fin_transaction_no IS NOT NULL)
                    """, user) == 0, "Synthetic payments have financial identifiers");
            long payments = count("SELECT COUNT(*) FROM payment_transaction WHERE user_id=?", user);
            System.out.printf("%s userId=%d payments=%d expectedInitial=%d enriched=%s%n", p.key(), user,
                    payments, QaHistoryPlan.build(p.scenario(), spec.baseDate()).size(),
                    db.queryForObject("SELECT enriched FROM qa_seed_member WHERE user_id=?", Boolean.class, user));
        }
    }

    private void inspect(QaSeedSpec spec) {
        for (var p : spec.personas()) {
            long user = owner(spec, p);
            System.out.printf("%s userId=%d%n", p.key(), user);
            for (String table : List.of("app_user", "user_device", "step_up_audit_log", "linked_account",
                    "user_card", "payment_transaction", "card_bill", "bill_item", "refund",
                    "cashflow_item", "cashflow_occurrence", "cashflow_payment_match", "cashflow_create_request",
                    "notification", "living_cost_prediction", "credit_spending_prediction")) {
                System.out.printf("  %s=%d%n", table, count("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", user));
            }
            for (String table : List.of("payment_benefit", "payment_limit_usage")) {
                System.out.printf("  %s=%d%n", table, count("SELECT COUNT(*) FROM " + table
                        + " x JOIN payment_transaction p USING(payment_transaction_id) WHERE p.user_id=?", user));
            }
            for (String table : List.of("card_monthly_performance", "monthly_limit_usage")) {
                System.out.printf("  %s=%d%n", table, count("SELECT COUNT(*) FROM " + table
                        + " x JOIN user_card c USING(user_card_id) WHERE c.user_id=?", user));
            }
            System.out.printf("  user_device_event=%d%n", count(
                    "SELECT COUNT(*) FROM user_device_event e JOIN user_device d USING(user_device_id) WHERE d.user_id=?", user));
            System.out.printf("  bill_item_discount=%d%n", count(
                    "SELECT COUNT(*) FROM bill_item_discount d JOIN card_bill b USING(card_bill_id) WHERE b.user_id=?", user));
            System.out.println("  deviceIds=" + db.queryForList(
                    "SELECT user_device_id FROM user_device WHERE user_id=? ORDER BY user_device_id", Long.class, user));
            System.out.println("  bills=" + db.queryForList("""
                    SELECT card_bill_id,linked_account_id,status,withdrawal_date,confirmed_amount,paid_amount,
                      fin_request_no,fin_transaction_no FROM card_bill WHERE user_id=? ORDER BY card_bill_id
                    """, user));
            System.out.println("  refunds=" + db.queryForList("""
                    SELECT refund_id,bill_item_id,linked_account_id,status,due_date,refund_amount,
                      fin_request_no,fin_transaction_no FROM refund WHERE user_id=? ORDER BY refund_id
                    """, user));
        }
    }

    private void remove(QaSeedSpec spec) {
        for (var p : spec.personas()) {
            long user = owner(spec, p);
            new QaSeedCleanup(db).remove(user);
            require(count("SELECT COUNT(*) FROM app_user WHERE user_id=?", user) == 0, "Cleanup incomplete");
        }
        db.update("DELETE FROM qa_seed_dataset WHERE dataset_key=?", spec.datasetKey());
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static String last4(String value) { return value.substring(value.length() - 4); }
    private long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    private long id(String sql, Object... args) {
        List<Long> ids = db.queryForList(sql, Long.class, args);
        require(ids.size() == 1, "Required binding missing or ambiguous");
        return ids.get(0);
    }
    long insert(String sql, Object... args) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
            try (var result = statement.getGeneratedKeys()) {
                require(result.next(), "Insert did not return an ID");
                return result.getLong(1);
            }
        }
    }
}
