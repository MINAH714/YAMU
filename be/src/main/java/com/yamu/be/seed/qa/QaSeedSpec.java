package com.yamu.be.seed.qa;

import com.yamu.be.global.crypto.PersonalDataNormalizer;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public scenario settings and injected financial bindings; never log this record. */
public record QaSeedSpec(String datasetKey, int version, LocalDate baseDate, List<Persona> personas) {
    public enum Scenario { RICH, EMPTY, BOUNDARY, SHORTFALL, STATES, LIFE }
    public record Account(String bankCode, String accountNo) {
        @Override public String toString() { return "Account[REDACTED]"; }
    }
    public record Card(String issuerCode, String productName, String cardType, String cardNo,
                       int accountIndex, Integer paymentDay,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) LocalDate issuerRegisteredOn,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean ibkSalaryQualified,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean ibkMilitaryQualified) {
        public Card(String issuerCode, String productName, String cardType, String cardNo, int accountIndex, Integer paymentDay) {
            this(issuerCode,productName,cardType,cardNo,accountIndex,paymentDay,null,null,null);
        }
        @Override public String toString() { return "Card[REDACTED]"; }
    }
    public record Persona(String key, Scenario scenario, String name, LocalDate birthDate, String phone,
                          String ssafyMemberId, String ssafyUserKey, List<Account> accounts, List<Card> cards, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String issuanceEligibility) {
        public Persona(String key, Scenario scenario, String name, LocalDate birthDate, String phone,
                String ssafyMemberId, String ssafyUserKey, List<Account> accounts, List<Card> cards) {
            this(key,scenario,name,birthDate,phone,ssafyMemberId,ssafyUserKey,accounts,cards,null);
        }
        @Override public String toString() { return "Persona[REDACTED]"; }
    }
    @Override public String toString() { return "QaSeedSpec[REDACTED]"; }

    public void validate(LocalDate today) {
        require(datasetKey != null && datasetKey.matches("qa-[a-z0-9-]{1,60}"), "Invalid dataset key");
        require(version == 1 || version == 2 || version == 3, "Unsupported seed version");
        require(baseDate != null && !baseDate.isAfter(today), "Reference date must not be in the future");
        require(personas != null && !personas.isEmpty() && personas.size() <= 20, "Expected 1..20 personas");
        Set<String> keys = new HashSet<>(), people = new HashSet<>(), members = new HashSet<>(),
                accountsSeen = new HashSet<>(), cardsSeen = new HashSet<>();
        for (Persona p : personas) {
            if (p != null && p.scenario() == Scenario.LIFE)
                require(version == 3 && QaLifeScenario.BASE.equals(baseDate) && personas.size() == 1,
                        "LIFE requires version 3, fixed date and one independently owned persona");
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
            require(p.accounts() != null && p.accounts().size() == (version == 3 ? 3 : 2), "Account count does not match seed version");
            for (Account a : p.accounts()) {
                require(a != null && a.bankCode() != null && a.bankCode().matches("[0-9]{3}")
                        && a.accountNo() != null && a.accountNo().matches("[0-9]{8,30}")
                        && accountsSeen.add(a.bankCode() + ":" + a.accountNo()), "Invalid or shared account");
            }
            require(p.cards() != null && p.cards().size() == (version == 3 ? 4 : 3), "Card count does not match seed version");
            for (int i = 0; i < p.cards().size(); i++) {
                Card c = p.cards().get(i);
                require(c != null && c.cardNo() != null && c.cardNo().matches("[0-9]{16}")
                        && cardsSeen.add(c.cardNo()), "Invalid or duplicate mock card");
                require(c.accountIndex() >= 0 && c.accountIndex() < p.accounts().size(), "Invalid account index");
                require(c.issuerCode() != null && c.productName() != null, "Missing catalog identity");
                require((i == 0 || i == 3 ? "CHECK" : "CREDIT").equals(c.cardType()), "Card slot type mismatch");
                require(i == 0 || i == 3 ? c.paymentDay() == null
                        : c.paymentDay() != null && c.paymentDay() >= 1 && c.paymentDay() <= 27,
                        "Invalid payment day");
            }
            if (version == 3) {
                Card ibk = p.cards().get(3);
                require(ibk.issuerRegisteredOn() != null && ibk.issuerRegisteredOn().isBefore(baseDate.withDayOfMonth(1).minusMonths(1))
                        && Boolean.FALSE.equals(ibk.ibkSalaryQualified()) && Boolean.FALSE.equals(ibk.ibkMilitaryQualified()),
                        "Existing non-military IBK holder with no salary waiver required");
                require("MILITARY_EXAM_COMPLETED".equals(p.issuanceEligibility())
                        && !p.birthDate().plusYears(19).isAfter(today), "IBK QA issuance eligibility required");
                require("KB".equals(p.cards().get(0).issuerCode())
                        && "004".equals(p.accounts().get(p.cards().get(0).accountIndex()).bankCode())
                        && "088".equals(p.accounts().get(p.cards().get(1).accountIndex()).bankCode())
                        && "088".equals(p.accounts().get(p.cards().get(2).accountIndex()).bankCode())
                        && "IBK".equals(p.cards().get(3).issuerCode())
                        && "IBK나라사랑카드".equals(p.cards().get(3).productName())
                        && "003".equals(p.accounts().get(p.cards().get(3).accountIndex()).bankCode()),
                        "QA issuer and linked bank must match");
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
