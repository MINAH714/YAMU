package com.yamu.be.user;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴한 사람의 재가입(11번 계획 (B), 10/30 승인). 같은 행을 되살리지 않고 새 사용자를 만든다.
 *
 * <pre>
 * 옛 행(WITHDRAWN) 잠금 → 열쇠(SSAFY 회원 ID·userKey)를 꺼내 옛 행의 phone_hash·birth_date·열쇠를 비움
 *   → 새 PENDING 사용자(이번 본인인증의 이름·번호 + 옮긴 열쇠) → 옛 카드 행을 새 PENDING 카드로 복사 → 옛 카드 번호 덮어쓰기
 * </pre>
 *
 * <ul>
 *   <li>예전 거래·청구 이력은 옛 user_id에 남아 새 계정에 보이지 않는다(실제 서비스처럼 재가입은 새 계정)</li>
 *   <li>탈퇴 때 이름을 지워 이름으로는 같은 사람인지 볼 수 없다. 휴대폰 번호·생년월일로 찾고, 실제 본인 확인은 다음 단계인
 *       1원 인증(그 SSAFY 회원의 계좌)이 맡는다</li>
 *   <li>옛 행의 열쇠를 먼저 비우고 flush한 뒤 새 행을 넣는다. UNIQUE(phone_hash, birth_date)·(ssafy_member_id)가 겹치지 않게</li>
 *   <li>같은 사람이 동시에 두 번 오면 옛 행 잠금으로 줄을 선다. 늦은 쪽은 열쇠가 이미 옮겨진 것을 보고 empty를 돌려받아
 *       새 사용자를 다시 찾는다(새 사용자는 하나만 생김)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RejoinService {

    private final AppUserRepository userRepository;
    private final PersonalDataCipher cipher;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * 탈퇴한 사용자 행으로 새 가입 사용자를 만든다.
     *
     * @return 새 사용자 번호. 그 사이 다른 요청이 이미 옮겼으면 empty(호출하는 쪽이 다시 찾는다)
     * @throws BusinessException 탈퇴 행에 SSAFY 회원이 없으면 DEMO_USER_NOT_FOUND(계좌를 연결할 수 없어 가입을 시작시키지 않음)
     */
    public Optional<Long> rejoin(Long withdrawnUserId, String name, LocalDate birthDate, byte[] phoneEnc, byte[] phoneHash,
            LocalDateTime verifiedAt) {
        return transaction.execute(status -> {
            AppUser old = userRepository.findByIdForUpdate(withdrawnUserId).orElse(null);
            if (old == null || !old.isWithdrawn() || old.getPhoneHash() == null
                    || !Arrays.equals(old.getPhoneHash(), phoneHash) || !birthDate.equals(old.getBirthDate())) {
                return Optional.<Long>empty();
            }
            String memberId = old.getSsafyMemberId();
            byte[] userKey = old.getSsafyUserKey();
            if (memberId == null || userKey == null) {
                throw new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND);
            }
            old.releaseRejoinKeys();
            userRepository.flush();

            AppUser fresh = AppUser.pending(name, birthDate, phoneEnc, phoneHash, verifiedAt);
            fresh.linkFinMember(memberId, userKey);
            userRepository.saveAndFlush(fresh);
            moveCards(old.getId(), fresh.getId());
            return Optional.of(fresh.getId());
        });
    }

    /**
     * 옛 카드 행(카드사가 준 데이터: 상품·번호·카드사 계좌·결제일·카드사 상태)을 새 사용자의 등록 전(PENDING) 카드로 복사하고,
     * 옛 행의 카드번호 암호문·조회 해시는 아무 의미 없는 값으로 덮는다(두 칸 모두 NOT NULL). 옛 행은 거래 이력이 가리켜 남긴다.
     */
    private void moveCards(Long oldUserId, Long newUserId) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO user_card (user_id, card_product_id, card_no_enc, card_no_hash, card_last4, payment_day,
                    status, issuer_status, issuer_account_bank_code, issuer_account_hash, issuer_account_last4,
                    is_default, created_at, updated_at, version)
                SELECT :newUserId, card_product_id, card_no_enc, card_no_hash, card_last4, payment_day,
                    'PENDING', issuer_status, issuer_account_bank_code, issuer_account_hash, issuer_account_last4,
                    FALSE, :now, :now, 0
                FROM user_card WHERE user_id = :oldUserId ORDER BY user_card_id
                """, new MapSqlParameterSource("newUserId", newUserId).addValue("oldUserId", oldUserId).addValue("now", now));
        List<Long> oldCardIds = jdbc.queryForList("SELECT user_card_id FROM user_card WHERE user_id = :oldUserId",
                new MapSqlParameterSource("oldUserId", oldUserId), Long.class);
        for (Long cardId : oldCardIds) {
            jdbc.update("""
                    UPDATE user_card SET card_no_enc = :enc, card_no_hash = :hash, updated_at = :now, version = version + 1
                    WHERE user_card_id = :id
                    """, new MapSqlParameterSource("id", cardId)
                    .addValue("enc", cipher.encrypt(dummy(), CipherField.USER_CARD_NO))
                    .addValue("hash", randomBytes(32)).addValue("now", now));
        }
    }

    private String dummy() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24));
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }
}
