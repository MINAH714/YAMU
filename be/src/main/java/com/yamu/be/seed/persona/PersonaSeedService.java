package com.yamu.be.seed.persona;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.PersonalDataNormalizer;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.seed.persona.PersonaSeedFile.Card;
import com.yamu.be.seed.persona.PersonaSeedFile.Persona;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입 시연용 페르소나를 넣는다(Story 4.11). 회원가입이 "시드에 있는 사람만"이라(10/30 결정) 가입을 시연하려면
 * 가입 직전 상태의 사용자가 DB에 있어야 한다.
 *
 * <pre>
 * app_user  PENDING, 이름·생년월일·휴대폰(암호문·조회 해시)·SSAFY 회원 ID·userKey(암호문). PIN·본인인증 시각은 비움
 * user_card PENDING, linked_account_id 없음. 카드사가 정한 계좌는 issuer_account_*(은행·조회 해시·끝 4자리)로,
 *           신용카드 결제일은 payment_day로 적는다. 카드 등록 때 연결 계좌와 대조해 linked_account_id가 채워진다(!47)
 * payment_transaction·card_monthly_performance  카드 등록 전 6개월 모의 소비(SEED)와 그 실적({@link PersonaSpendingHistory})
 * 넣지 않음 linked_account(가입 중 계좌 목록에서 골라 연결), user_device(가입의 마지막 단계)
 * </pre>
 *
 * <ul>
 *   <li>암호화·해시는 앱과 같은 키(앱 설정)로 만든다. 그래서 SQL 파일이 아니라 서버 코드로 넣는다.</li>
 *   <li>넣기 전에 SSAFY 계좌 목록을 읽어 userKey가 맞고 계좌가 1개 이상인지, 카드 계좌가 그 목록에 있는지 본다.
 *       금융망 호출 중에는 DB 연결을 잡지 않는다.</li>
 *   <li>페르소나마다 트랜잭션이 따로라 한 명이 실패해도 다른 사람은 들어간다.</li>
 *   <li>같은 사람(휴대폰·생년월일)이 있으면 건너뛴다. 초기화(reset)는 아직 PENDING인 사람만 지우고 다시 넣는다.
 *       시연 중 가입을 마친(ACTIVE) 사람은 건드리지 않는다.</li>
 *   <li>로그에는 순번·이름표·결과만 남긴다. 이름·번호·userKey·카드번호는 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaSeedService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookup;
    private final PersonaAccountCheck accountCheck;
    private final Validator validator;
    private final Clock clock;
    private final PersonaSpendingHistory history;

    public enum Result {
        CREATED, RESET, SKIPPED_EXISTS, SKIPPED_NOT_PENDING, FAILED
    }

    /** @param message 실패·건너뜀 사유. 개인정보를 넣지 않는다 */
    public record Outcome(String persona, Result result, String message) {
    }

    public record Report(List<Outcome> outcomes) {
        public boolean hasFailure() {
            return outcomes.stream().anyMatch(o -> o.result() == Result.FAILED);
        }
    }

    public Report seed(List<Persona> personas, boolean reset) {
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> seenPeople = new HashSet<>();
        Set<String> seenMembers = new HashSet<>();
        for (int i = 0; i < personas.size(); i++) {
            Persona persona = personas.get(i);
            String tag = persona == null ? "#" + (i + 1) : persona.tag(i + 1);
            Outcome outcome;
            try {
                outcome = seedOne(tag, persona, reset, seenPeople, seenMembers);
            } catch (SeedFailure e) {
                outcome = new Outcome(tag, Result.FAILED, e.getMessage());
            } catch (BusinessException e) {
                // 정규화 실패(예: 01로 시작하지 않는 휴대폰). 메시지에 값이 들어 있지 않다.
                outcome = new Outcome(tag, Result.FAILED, e.getMessage());
            } catch (RuntimeException e) {
                // 예외 메시지에 값(중복 키 등)이 들어 있을 수 있어 종류만 남긴다.
                outcome = new Outcome(tag, Result.FAILED, "예상하지 못한 오류(" + e.getClass().getSimpleName() + ")");
            }
            log(outcome);
            outcomes.add(outcome);
        }
        return new Report(List.copyOf(outcomes));
    }

    private Outcome seedOne(String tag, Persona persona, boolean reset, Set<String> seenPeople, Set<String> seenMembers) {
        if (persona == null) {
            throw new SeedFailure("빈 항목입니다.");
        }
        validate(persona);
        String phone = PersonalDataNormalizer.phone(persona.phone());
        byte[] phoneHash = lookup.phone(phone);
        if (!seenPeople.add(Arrays.toString(phoneHash) + persona.birthDate())) {
            throw new SeedFailure("같은 파일 안에 같은 사람(휴대폰·생년월일)이 또 있습니다.");
        }
        if (!seenMembers.add(persona.ssafyMemberId())) {
            throw new SeedFailure("같은 파일 안에 같은 ssafyMemberId가 또 있습니다.");
        }
        List<ResolvedCard> cards = resolveCards(persona.cards());
        requireCardSettings(cards);

        Existing existing = findExisting(phoneHash, persona.birthDate());
        if (existing != null && !reset) {
            return new Outcome(tag, Result.SKIPPED_EXISTS, "이미 있어 건너뜀(status=" + existing.status() + ")"
                    + staleHistory(existing));
        }
        if (existing != null && !"PENDING".equals(existing.status())) {
            return new Outcome(tag, Result.SKIPPED_NOT_PENDING,
                    "가입을 마쳤거나 탈퇴한 사용자라 초기화하지 않음(status=" + existing.status() + ")");
        }
        requireSsafyAccounts(persona.ssafyUserKey(), cards);

        try {
            transaction.executeWithoutResult(status -> {
                if (existing != null) {
                    deletePending(existing.userId());
                }
                insert(persona, phone, phoneHash, cards);
            });
        } catch (DuplicateKeyException e) {
            // 다른 실행이 같은 사람을 먼저 넣었거나, SSAFY 회원 ID를 다른 사용자가 이미 쓰는 경우다.
            if (findExisting(phoneHash, persona.birthDate()) != null) {
                return new Outcome(tag, Result.SKIPPED_EXISTS, "동시에 다른 실행이 먼저 넣어 건너뜀");
            }
            throw new SeedFailure("ssafyMemberId 또는 카드번호가 다른 데이터와 겹칩니다.");
        }
        return new Outcome(tag, existing == null ? Result.CREATED : Result.RESET, null);
    }

    private void validate(Persona persona) {
        Set<ConstraintViolation<Persona>> violations = validator.validate(persona);
        if (!violations.isEmpty()) {
            String messages = violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            throw new SeedFailure("입력 오류 - " + messages);
        }
        Set<String> cardNos = new HashSet<>();
        for (Card card : persona.cards()) {
            if (!cardNos.add(card.cardNo())) {
                throw new SeedFailure("같은 카드번호가 두 번 있습니다.");
            }
        }
    }

    /** 상품 이름으로 찾는다. 이름이 카드사·종류별로 겹치면 issuerCode·cardType을 넣어야 한다. */
    private List<ResolvedCard> resolveCards(List<Card> cards) {
        List<ResolvedCard> resolved = new ArrayList<>();
        for (Card card : cards) {
            StringBuilder sql = new StringBuilder("SELECT card_product_id, card_type FROM card_product WHERE product_name = ?");
            List<Object> args = new ArrayList<>(List.of(card.productName()));
            if (card.issuerCode() != null && !card.issuerCode().isBlank()) {
                sql.append(" AND issuer_code = ?");
                args.add(card.issuerCode());
            }
            if (card.cardType() != null) {
                sql.append(" AND card_type = ?");
                args.add(card.cardType());
            }
            List<ResolvedCard> ids = jdbc.query(sql.toString(),
                    (rs, n) -> new ResolvedCard(rs.getLong(1), rs.getString(2), card), args.toArray());
            if (ids.isEmpty()) {
                throw new SeedFailure("카드 상품을 찾을 수 없습니다(" + card.productName()
                        + "). 카드 카탈로그 시드를 먼저 넣었는지 확인하세요.");
            }
            if (ids.size() > 1) {
                throw new SeedFailure("카드 상품 이름이 여러 개와 겹칩니다(" + card.productName()
                        + "). issuerCode·cardType을 같이 넣어 주세요.");
            }
            resolved.add(ids.get(0));
        }
        return resolved;
    }

    /**
     * 카드 종류에 맞는 설정인지 본다. 신용카드는 결제일이 있어야 하고(카드 등록이 이 값을 쓴다),
     * 체크카드는 결제일이 없다(결제할 때마다 바로 출금).
     */
    private static void requireCardSettings(List<ResolvedCard> cards) {
        for (int i = 0; i < cards.size(); i++) {
            ResolvedCard card = cards.get(i);
            boolean credit = "CREDIT".equals(card.cardType());
            if (credit && card.card().paymentDay() == null) {
                throw new SeedFailure("카드 " + (i + 1) + "번째는 신용카드라 paymentDay(1~27)가 필요합니다.");
            }
            if (!credit && card.card().paymentDay() != null) {
                throw new SeedFailure("카드 " + (i + 1) + "번째는 체크카드라 paymentDay를 넣지 않습니다.");
            }
        }
    }

    /**
     * 계좌가 1개 이상 있고, 카드마다 정한 계좌가 그 목록에 있는지 본다. 목록에 없는 계좌를 적으면 가입 중 연결할 수 없어
     * 카드 등록이 항상 409(CARD_ACCOUNT_NOT_LINKED)가 된다. 메시지에는 카드 순번·은행 코드만 넣는다(계좌번호 없음).
     */
    private void requireSsafyAccounts(String userKey, List<ResolvedCard> cards) {
        Set<String> accounts;
        try {
            accounts = accountCheck.accountKeys(userKey);
        } catch (FinApiException e) {
            throw new SeedFailure("SSAFY 계좌 목록 조회 실패(" + e.getErrorCode().name() + ", "
                    + e.getOutcome() + "). userKey와 SSAFY_API_KEY를 확인하세요.");
        }
        if (accounts.isEmpty()) {
            throw new SeedFailure("SSAFY 회원에 수시입출금 계좌가 없습니다. 계좌를 먼저 만들고 다시 실행하세요.");
        }
        for (int i = 0; i < cards.size(); i++) {
            PersonaSeedFile.Account account = cards.get(i).card().account();
            String key = PersonalDataNormalizer.accountLookupKey(account.bankCode(), account.accountNo());
            if (!accounts.contains(key)) {
                throw new SeedFailure(String.format(Locale.ROOT,
                        "카드 %d번째의 account(은행 %s)가 이 SSAFY 회원의 계좌 목록에 없습니다.", i + 1, account.bankCode()));
            }
        }
    }

    /**
     * 아직 가입 전(PENDING)인데 등록 전 모의 소비를 오늘 전에 넣었거나 아예 없으면 --reset 안내를 붙인다. 모의 소비는 넣은 날
     * 전날까지만 있어서, 그 뒤 빈 날은 잔고예보 생활비에서 소비 0원인 날로 세져 평균이 낮아진다(2026-11-05 결정: 자동으로
     * 채우지 않고 알리기만 한다). 결제가 없는 날도 있으므로 마지막 결제일이 아니라 넣은 날로 본다.
     */
    private String staleHistory(Existing existing) {
        if (!"PENDING".equals(existing.status())) {
            return "";
        }
        Timestamp seededAt = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM payment_transaction WHERE user_id = ? AND source = 'SEED'",
                Timestamp.class, existing.userId());
        if (seededAt == null) {
            return ". 등록 전 모의 소비가 없습니다. 잔고예보 생활비를 보이려면 --reset으로 다시 넣으세요";
        }
        LocalDate seededDay = seededAt.toInstant().atZone(KOREA).toLocalDate();
        return seededDay.isBefore(LocalDate.now(clock.withZone(KOREA)))
                ? ". 등록 전 모의 소비는 " + seededDay.minusDays(1) + "까지만 있습니다. 가입 시연 전에 --reset으로 다시 넣으세요"
                : "";
    }

    private Existing findExisting(byte[] phoneHash, LocalDate birthDate) {
        List<Existing> rows = jdbc.query("SELECT user_id, status FROM app_user WHERE phone_hash = ? AND birth_date = ?",
                (rs, n) -> new Existing(rs.getLong(1), rs.getString(2)), phoneHash, birthDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 아직 PENDING인지 행을 잠그고 다시 본 뒤 지운다. 그 사이 가입을 마쳤으면 지우지 않는다.
     * 가입 중 연결한 계좌·카드와, 시드가 넣은 등록 전 모의 소비·실적도 같이 지운다. 그 밖의 데이터가 걸려 있으면 FK가 막아
     * 이 페르소나만 실패한다.
     */
    private void deletePending(long userId) {
        List<String> status = jdbc.queryForList("SELECT status FROM app_user WHERE user_id = ? FOR UPDATE",
                String.class, userId);
        if (status.isEmpty() || !"PENDING".equals(status.get(0))) {
            throw new SeedFailure("초기화 중에 상태가 바뀌어 건너뜀");
        }
        try {
            // 시드가 넣은 결제(SEED)만 지운다. 다른 결제가 남아 있으면 카드 삭제가 FK에 막혀 전체가 되돌아간다.
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ? AND source = 'SEED'", userId);
            jdbc.update("DELETE p FROM card_monthly_performance p JOIN user_card c USING (user_card_id) WHERE c.user_id = ?",
                    userId);
            jdbc.update("DELETE FROM user_card WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM linked_account WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE user_id = ?", userId);
        } catch (DataIntegrityViolationException e) {
            throw new SeedFailure("이 사용자에 다른 데이터가 연결돼 있어 초기화할 수 없습니다.");
        }
    }

    private void insert(Persona persona, String phone, byte[] phoneHash, List<ResolvedCard> cards) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO app_user (name, birth_date, phone_enc, phone_hash, ssafy_member_id, ssafy_user_key,
                    status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, persona.name(), persona.birthDate(), cipher.encrypt(phone, CipherField.APP_USER_PHONE), phoneHash,
                persona.ssafyMemberId(), cipher.encrypt(persona.ssafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY),
                now, now);
        Long userId = jdbc.queryForObject("SELECT user_id FROM app_user WHERE phone_hash = ? AND birth_date = ?",
                Long.class, phoneHash, persona.birthDate());
        List<PersonaSpendingHistory.SeededCard> seeded = new ArrayList<>();
        for (ResolvedCard resolved : cards) {
            Card card = resolved.card();
            String cardNo = PersonalDataNormalizer.cardNo(card.cardNo());
            // 계좌 연결(AccountLinkService)과 같은 해시라 카드 등록 때 linked_account.account_no_hash와 그대로 대조된다.
            String accountNo = PersonalDataNormalizer.accountNo(card.account().accountNo());
            jdbc.update("""
                    INSERT INTO user_card (user_id, card_product_id, linked_account_id, issuer_account_bank_code,
                        issuer_account_hash, issuer_account_last4, card_no_enc, card_no_hash, card_last4, payment_day,
                        status, issuer_status, created_at, updated_at)
                    VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                    """, userId, resolved.productId(), card.account().bankCode(),
                    lookup.account(card.account().bankCode(), accountNo), accountNo.substring(accountNo.length() - 4),
                    cipher.encrypt(cardNo, CipherField.USER_CARD_NO), lookup.card(cardNo),
                    cardNo.substring(cardNo.length() - 4), card.paymentDay(), card.issuerStatus().name(), now, now);
            Long cardId = jdbc.queryForObject("SELECT user_card_id FROM user_card WHERE user_id = ? AND card_no_hash = ?",
                    Long.class, userId, lookup.card(cardNo));
            seeded.add(new PersonaSpendingHistory.SeededCard(cardId, resolved.productId(), resolved.cardType()));
        }
        history.insert(userId, persona.ssafyMemberId(), seeded, LocalDate.now(clock.withZone(KOREA)), now);
    }

    private static void log(Outcome outcome) {
        switch (outcome.result()) {
            case CREATED, RESET -> log.info("페르소나 {} {}", outcome.persona(), outcome.result());
            case SKIPPED_EXISTS -> log.info("페르소나 {} 건너뜀: {}", outcome.persona(), outcome.message());
            case SKIPPED_NOT_PENDING -> log.warn("페르소나 {} 건너뜀: {}", outcome.persona(), outcome.message());
            case FAILED -> log.error("페르소나 {} 실패: {}", outcome.persona(), outcome.message());
        }
    }

    private record Existing(long userId, String status) {
    }

    private record ResolvedCard(long productId, String cardType, Card card) {
    }

    /** 이 페르소나만 실패. 메시지는 그대로 사람에게 보이므로 개인정보를 넣지 않는다. */
    static final class SeedFailure extends RuntimeException {
        SeedFailure(String message) {
            super(message);
        }
    }
}
