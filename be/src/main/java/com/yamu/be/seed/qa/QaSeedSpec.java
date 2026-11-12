package com.yamu.be.seed.qa;

import com.yamu.be.global.crypto.PersonalDataNormalizer;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public scenario settings and injected financial bindings; never log this record. */
public record QaSeedSpec(String datasetKey, int version, LocalDate baseDate, List<Persona> personas) {
    public enum Scenario { RICH, EMPTY, BOUNDARY, SHORTFALL, STATES }
    public record Account(String bankCode, String accountNo) {
        @Override public String toString() { return "Account[REDACTED]"; }
    }
    public record Card(String issuerCode, String productName, String cardType, String cardNo,
                       int accountIndex, Integer paymentDay) {
        @Override public String toString() { return "Card[REDACTED]"; }
    }
    public record Persona(String key, Scenario scenario, String name, LocalDate birthDate, String phone,
                          String ssafyMemberId, String ssafyUserKey, List<Account> accounts, List<Card> cards) {
        @Override public String toString() { return "Persona[REDACTED]"; }
    }
    @Override public String toString() { return "QaSeedSpec[REDACTED]"; }

    public void validate(LocalDate today) {
        require(datasetKey != null && datasetKey.matches("qa-[a-z0-9-]{1,60}"), "Invalid dataset key");
        require(version == 1, "Unsupported seed version");
        require(baseDate != null && !baseDate.isAfter(today), "Reference date must not be in the future");
        require(personas != null && !personas.isEmpty() && personas.size() <= 20, "Expected 1..20 personas");
        Set<String> keys = new HashSet<>(), people = new HashSet<>(), members = new HashSet<>(),
                accountsSeen = new HashSet<>(), cardsSeen = new HashSet<>();
        for (Persona p : personas) {
            require(p != null && p.key() != null && p.key().matches("[a-z0-9-]{1,32}")
                    && keys.add(p.key()) && p.scenario() != null, "Invalid or duplicate persona");
            require(p.name() != null && p.name().length() >= 2 && p.name().length() <= 20
                    && p.birthDate() != null && p.birthDate().isBefore(today), "Invalid identity");
            require(people.add(PersonalDataNormalizer.phone(p.phone()) + ":" + p.birthDate()),
                    "Duplicate identity");
            require(p.ssafyMemberId() != null && p.ssafyMemberId().matches("[^\\s@]{1,64}@[^\\s@]{1,190}")
                    && members.add(p.ssafyMemberId()), "Invalid or duplicate financial member");
            require(p.ssafyUserKey() != null && !p.ssafyUserKey().isBlank()
                    && p.ssafyUserKey().length() <= 200, "Missing financial binding");
            require(p.accounts() != null && p.accounts().size() == 2, "Two financial accounts required");
            for (Account a : p.accounts()) {
                require(a != null && a.bankCode() != null && a.bankCode().matches("[0-9]{3}")
                        && a.accountNo() != null && a.accountNo().matches("[0-9]{8,30}")
                        && accountsSeen.add(a.bankCode() + ":" + a.accountNo()), "Invalid or shared account");
            }
            require(p.cards() != null && p.cards().size() == 3, "Three card slots required");
            for (int i = 0; i < 3; i++) {
                Card c = p.cards().get(i);
                require(c != null && c.cardNo() != null && c.cardNo().matches("[0-9]{16}")
                        && cardsSeen.add(c.cardNo()), "Invalid or duplicate mock card");
                require(c.accountIndex() >= 0 && c.accountIndex() < 2, "Invalid account index");
                require(c.issuerCode() != null && c.productName() != null, "Missing catalog identity");
                require((i == 0 ? "CHECK" : "CREDIT").equals(c.cardType()), "Card slot type mismatch");
                require(i == 0 ? c.paymentDay() == null
                        : c.paymentDay() != null && c.paymentDay() >= 1 && c.paymentDay() <= 27,
                        "Invalid payment day");
            }
            require("SHINHAN".equals(p.cards().get(1).issuerCode())
                    && "신한카드 처음".equals(p.cards().get(1).productName())
                    && "SHINHAN".equals(p.cards().get(2).issuerCode())
                    && "신한카드 Mr.Life".equals(p.cards().get(2).productName()),
                    "Credit slots must match the documented fixture policies");
        }
    }

    static void require(boolean condition, String safeMessage) {
        if (!condition) throw new IllegalArgumentException(safeMessage);
    }
}
