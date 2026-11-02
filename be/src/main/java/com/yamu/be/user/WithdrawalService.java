package com.yamu.be.user;

import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.auth.RefreshTokenStore;
import com.yamu.be.auth.SignupTokenStore;
import com.yamu.be.auth.StepUpService;
import com.yamu.be.auth.domain.DeviceEventType;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.auth.domain.UserDevice;
import com.yamu.be.auth.domain.UserDeviceEvent;
import com.yamu.be.auth.domain.UserDeviceEventRepository;
import com.yamu.be.auth.domain.UserDeviceRepository;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 탈퇴(명세 「회원 탈퇴」, DEC-001·FR-071). 11번 계획(10/30 승인)의 결정을 따른다.
 *
 * <pre>
 * 끝나지 않은 청구·환급 확인(409) → 본인 인증(WITHDRAW_MEMBER) 소비 → 사용자 행 잠금 안에서 한 번에 정리 → 커밋 뒤 Redis 정리
 * </pre>
 *
 * <ul>
 *   <li>남기는 것(재가입 열쇠): phone_hash·birth_date·이름 해시(withdrawn_name_hash, 임시)·SSAFY 회원 ID·userKey,
 *       카드 행의 카드번호·카드사 계좌·결제일. 이름 해시는 재가입 때 이름까지 맞는지 보려고 남긴다(이름 원문은 지움).
 *       재가입하면 새 사용자로 옮겨지고 이 행에서는 지워진다({@link RejoinService}). SSAFY 계정은 지우는 API가 없어 그대로다</li>
 *   <li>지우는 것: 이름·휴대폰 암호문·PIN·본인인증 시각(NULL), 계좌번호(무작위 값으로 덮음, NOT NULL), 기기 공개키(무작위, NOT NULL),
 *       기기 이름(NULL, "OO의 갤럭시"처럼 이름이 들어갈 수 있음),
 *       본인 인증 기록의 IP·User-Agent(NULL), Redis의 Refresh Token·가입 토큰·잔액 캐시</li>
 *   <li>행은 지우지 않는다. 거래·청구·감사 로그가 FK(RESTRICT)로 가리키기 때문이다. 이력은 옛 user_id에 남고 재가입한 새 계정에는 보이지 않는다</li>
 *   <li>하지 않는 것(확인 필요): 30일 뒤 이력 삭제, 탈퇴 철회 기간, 쌓이는 중(ACCUMULATING) 청구 회차 정리(BE 2),
 *       예정 수입·지출(BE 4, cashflow_item의 제목·메모 포함). 쌓이는 중 청구도 "끝나지 않은 청구"라 탈퇴를 막는다</li>
 *   <li>끝나지 않은 청구·환급은 본인 인증 전에 한 번(토큰을 버리지 않게), 사용자 행을 잠근 뒤 한 번 더 본다. 청구를 만드는 쪽(BE 2)이
 *       사용자 행을 잠그지 않으면 두 번째 확인 뒤 커밋 전에 생긴 청구는 막지 못한다(한계)</li>
 *   <li>커밋 뒤 Redis 정리가 실패해도 탈퇴는 끝났으므로 경고만 남기고 성공으로 응답한다. 남은 Refresh Token은 재발급 때
 *       DB의 기기·사용자 상태를 먼저 봐서 쓰이지 않고, 가입 토큰·잔액 캐시는 TTL로 사라진다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    /** 끝난 청구(카드 해제와 같은 기준). 이 상태가 아닌 청구가 있으면 탈퇴를 막는다. */
    private static final List<String> SETTLED_BILL_STATUSES = List.of("PAID", "CANCELED");
    /** 끝난 환급. refund.status 허용값이 아직 없어(DDL "상태 코드") 끝난 상태만 나열하고 나머지는 모두 막는다. BE 2와 이름을 맞춘다. */
    private static final List<String> SETTLED_REFUND_STATUSES = List.of("COMPLETED", "CANCELED");

    private final AppUserRepository userRepository;
    private final UserCardRepository cardRepository;
    private final LinkedAccountRepository accountRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserDeviceEventRepository eventRepository;
    private final StepUpService stepUpService;
    private final RefreshTokenStore refreshTokenStore;
    private final SignupTokenStore signupTokenStore;
    private final AccountBalanceService balanceService;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * 탈퇴한다. 끝나지 않은 청구·환급이 있으면 409이고 본인 인증 토큰은 쓰지 않는다.
     * 이미 탈퇴한 사용자(동시에 두 번 누름)는 바꾸지 않는다. 그 요청은 보통 본인 인증 단계에서 먼저 막힌다.
     *
     * @param reason 탈퇴 사유(선택, 통계용). 개인정보가 섞일 수 있어 저장하지 않는다(확인 필요)
     */
    public void withdraw(AuthUser me, String stepUpToken, String reason) {
        requireSettled(me.userId());
        stepUpService.requireStepUp(me, StepUpPurpose.WITHDRAW_MEMBER, stepUpToken);
        Cleanup cleanup = transaction.execute(status -> wipe(me.userId()));
        if (cleanup == null) {
            return;
        }
        afterCommit("Refresh Token 폐기", me.userId(), () -> cleanup.deviceIds().forEach(refreshTokenStore::revoke));
        afterCommit("가입 토큰 삭제", me.userId(), () -> signupTokenStore.deleteAllForUser(me.userId()));
        cleanup.accountIds().forEach(balanceService::evict);
        log.info("회원 탈퇴 처리 (userId={}, 기기 {}대, 계좌 {}개, 카드 {}장)", me.userId(), cleanup.deviceIds().size(),
                cleanup.accountIds().size(), cleanup.cardCount());
    }

    /** 커밋 뒤 Redis 정리. 실패해도 탈퇴는 이미 끝났으므로 경고만 남긴다(잔액 캐시 삭제는 evict가 스스로 삼킨다). */
    private void afterCommit(String step, Long userId, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.warn("탈퇴 뒤 {} 실패({}) → 탈퇴는 완료 (userId={})", step, e.getClass().getSimpleName(), userId);
        }
    }

    private void requireSettled(Long userId) {
        List<Long> billIds = jdbc.queryForList("""
                SELECT card_bill_id FROM card_bill WHERE user_id = :userId AND status NOT IN (:statuses) ORDER BY card_bill_id
                """, new MapSqlParameterSource("userId", userId).addValue("statuses", SETTLED_BILL_STATUSES), Long.class);
        if (!billIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BILL_OUTSTANDING, Map.of("billIds", billIds));
        }
        List<Long> refundIds = jdbc.queryForList("""
                SELECT refund_id FROM refund WHERE user_id = :userId AND status NOT IN (:statuses) ORDER BY refund_id
                """, new MapSqlParameterSource("userId", userId).addValue("statuses", SETTLED_REFUND_STATUSES), Long.class);
        if (!refundIds.isEmpty()) {
            throw new BusinessException(ErrorCode.REFUND_PENDING, Map.of("refundIds", refundIds));
        }
    }

    /** 사용자 행을 잠근 채 한 트랜잭션으로 정리한다. 이미 탈퇴했으면 null. */
    private Cleanup wipe(Long userId) {
        AppUser user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (user.isWithdrawn()) {
            return null;
        }
        // 처음 확인과 잠금 사이에 생긴 청구·환급. 본인 인증 토큰은 이미 썼지만 아무것도 바꾸지 않고 409로 돌려준다.
        requireSettled(userId);
        LocalDateTime now = LocalDateTime.now(clock);

        List<UserCard> cards = cardRepository.findByUserId(userId);
        cards.stream().filter(c -> c.getRegistrationStatus() != CardRegistrationStatus.UNREGISTERED)
                .forEach(UserCard::unregister);

        // 카드가 끊길 계좌를 가리키는 FK는 그대로 둔다(같은 사용자의 행이라 복합 FK가 깨지지 않음).
        List<LinkedAccount> accounts = accountRepository.findByUserId(userId);
        for (LinkedAccount account : accounts) {
            account.wipeForWithdrawal(cipher.encrypt(dummy(), CipherField.LINKED_ACCOUNT_NO), randomBytes(32), now);
        }

        List<UserDevice> devices = deviceRepository.findAllByUserId(userId);
        for (UserDevice device : devices) {
            boolean wasUsable = !device.isRevoked();
            device.wipeForWithdrawal("withdrawn:" + dummy());
            if (wasUsable) {
                eventRepository.save(new UserDeviceEvent(device.getId(), DeviceEventType.REVOKED, "회원 탈퇴"));
            }
        }

        user.withdraw(now, user.getName() == null ? null : lookupHasher.name(user.getName()));
        userRepository.flush();
        jdbc.update("UPDATE step_up_audit_log SET ip_address = NULL, user_agent = NULL WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId));
        return new Cleanup(devices.stream().map(UserDevice::getId).toList(),
                accounts.stream().map(LinkedAccount::getId).toList(), cards.size());
    }

    /** 원래 값으로 되돌릴 수 없는 무작위 문자열(덮어쓰기용). */
    private String dummy() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24));
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private record Cleanup(List<Long> deviceIds, List<Long> accountIds, int cardCount) {}
}
