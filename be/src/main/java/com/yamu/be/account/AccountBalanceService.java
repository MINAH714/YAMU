package com.yamu.be.account;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 연결 계좌 잔액. SSAFY {@code inquireDemandDepositAccountBalance}를 계좌마다 부르고, 계좌별로 10분 캐시한다.
 * 계좌 목록 화면과 잔고예보(BE 4)가 같은 캐시를 써서 두 화면의 잔액이 같고 금융망 호출도 한 번으로 끝난다.
 *
 * <p>금융망 호출은 DB 트랜잭션 밖에서 한다. 이 클래스에는 {@code @Transactional}을 붙이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountBalanceService {

    private static final String CACHE_PREFIX = "account:balance:";

    private final LinkedAccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final SsafyAccountApi ssafy;
    private final PersonalDataCipher cipher;
    private final StringRedisTemplate redis;
    private final AccountBalanceProperties properties;
    private final Clock clock;

    /**
     * 선택한 계좌들의 잔액. 잔고예보(BE 4)와 맞춘 계약이다(2026-10-29).
     *
     * <ul>
     *   <li>넘긴 순서대로 돌려준다. 같은 번호가 여러 번 오면 하나만 돌려준다. 빈 목록이면 빈 목록</li>
     *   <li>하나라도 없는 번호·남의 계좌·연결 해제한 계좌면 전체를 404 RESOURCE_NOT_FOUND로 거절한다. 금융망을 부르기 전에 확인한다</li>
     *   <li>금융망 실패는 공통 클라이언트 예외(502·504)를 그대로 던진다. 일부 계좌만 성공해도 결과를 돌려주지 않는다</li>
     *   <li>refresh=false면 10분 캐시를 쓰고, true면 캐시를 건너뛰고 새로 읽어 캐시를 바꾼다</li>
     * </ul>
     */
    public List<AccountBalance> getBalances(Long userId, List<Long> linkedAccountIds, boolean refresh) {
        if (linkedAccountIds == null || linkedAccountIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(linkedAccountIds));
        if (userId == null || ids.contains(null)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<Long, LinkedAccount> owned = connected(userId, ids);
        if (owned.size() != ids.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String userKey = userKeyOf(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        return ids.stream().map(id -> read(userKey, owned.get(id), refresh)).toList();
    }

    /**
     * 캐시에 있는 잔액만 돌려준다(금융망을 부르지 않는다). DB 트랜잭션 안에서 화면을 만드는 곳(체크카드 상세 등)이
     * 금융망 응답을 기다리며 DB 연결을 잡고 있지 않게 하려고 둔다. 10분 안에 계좌 목록·잔고예보가 읽었으면 그 값이 나온다.
     * 남의 계좌·해제 계좌·캐시 없음은 empty.
     */
    public Optional<AccountBalance> findCachedBalance(Long userId, Long linkedAccountId) {
        if (userId == null || linkedAccountId == null) {
            return Optional.empty();
        }
        return accountRepository.findByIdAndUserId(linkedAccountId, userId)
                .filter(LinkedAccount::isConnected)
                .flatMap(account -> cached(account.getId()));
    }

    /**
     * 계좌 목록 화면용. 계좌마다 따로 읽어, 금융망을 못 읽은 계좌만 결과에서 빠진다(화면은 그 계좌 잔액을 비워 보여 준다).
     * 호출하는 쪽이 이미 소유·연결 상태를 확인한 계좌를 넘긴다.
     */
    Map<Long, AccountBalance> readAvailable(Long userId, Collection<LinkedAccount> accounts, boolean refresh) {
        Map<Long, AccountBalance> balances = new LinkedHashMap<>();
        if (accounts.isEmpty()) {
            return balances;
        }
        Optional<String> userKey = userKeyOf(userId);
        if (userKey.isEmpty()) {
            return balances;
        }
        for (LinkedAccount account : accounts) {
            try {
                balances.put(account.getId(), read(userKey.get(), account, refresh));
            } catch (FinApiException e) {
                log.warn("계좌 {} 잔액 조회 실패({}) → 잔액 없이 응답", account.getId(), e.getOutcome());
            }
        }
        return balances;
    }

    private AccountBalance read(String userKey, LinkedAccount account, boolean refresh) {
        if (!refresh) {
            Optional<AccountBalance> cached = cached(account.getId());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        long balance = ssafy.balance(userKey, cipher.decrypt(account.getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO));
        AccountBalance fresh = new AccountBalance(account.getId(), balance, now());
        redis.opsForValue().set(cacheKey(account.getId()),
                balance + "|" + fresh.fetchedAt().toEpochSecond(ZoneOffset.UTC), properties.cacheTtl());
        return fresh;
    }

    /** 캐시 값은 "잔액|조회 시각(epoch 초)". 깨진 값은 없는 것으로 보고 새로 읽는다. */
    private Optional<AccountBalance> cached(Long accountId) {
        String value = redis.opsForValue().get(cacheKey(accountId));
        if (value == null) {
            return Optional.empty();
        }
        int bar = value.indexOf('|');
        try {
            long balance = Long.parseLong(value.substring(0, bar));
            long epochSecond = Long.parseLong(value.substring(bar + 1));
            return Optional.of(new AccountBalance(accountId, balance,
                    LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Map<Long, LinkedAccount> connected(Long userId, List<Long> ids) {
        return accountRepository.findByIdInAndUserIdAndStatus(ids, userId, LinkedAccountStatus.CONNECTED).stream()
                .collect(Collectors.toMap(LinkedAccount::getId, Function.identity()));
    }

    /** SSAFY 계정이 없는 사용자(시드로 넣은 가입 중 사용자 등)는 금융망을 부를 수 없다. */
    private Optional<String> userKeyOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getSsafyUserKey)
                .map(key -> cipher.decrypt(key, CipherField.APP_USER_SSAFY_USER_KEY));
    }

    private static String cacheKey(Long accountId) {
        return CACHE_PREFIX + accountId;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
