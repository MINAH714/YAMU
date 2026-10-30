package com.yamu.be.account;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.account.dto.AccountListResponse;
import com.yamu.be.account.dto.AccountResponse;
import com.yamu.be.account.dto.AccountUpdateRequest;
import com.yamu.be.account.dto.BankResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연결 계좌 목록·정보 수정과 기준 계좌. 명세: 노션 API 명세서 V2 「계좌 목록 조회」「계좌 정보 수정 (기준 계좌 지정)」.
 *
 * <p>계좌 연결 해제 API는 명세상 1차 범위에 없다(카드 결제계좌로 쓰는 계좌를 끊으면 청구 출금이 막혀서, 별도 정책과 함께 추가).
 *
 * <p>잔액은 금융망을 부르므로 DB 트랜잭션이 끝난 뒤 {@link AccountBalanceService}로 읽는다.
 */
@Service
@RequiredArgsConstructor
public class AccountManagementService {

    private final LinkedAccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final AccountBalanceService balanceService;
    private final AccountActivityReader activityReader;
    private final TransactionTemplate transaction;

    /** 연결 계좌 목록. 잔액을 못 읽은 계좌는 잔액만 비우고 목록은 보여 준다. */
    public AccountListResponse list(AuthUser me, boolean refresh) {
        List<LinkedAccount> accounts = accountRepository.findByUserIdAndStatusOrderByIdAsc(me.userId(),
                LinkedAccountStatus.CONNECTED);
        List<AccountResponse> responses = responses(me.userId(), accounts, refresh);
        LocalDateTime syncedAt = responses.stream().map(AccountResponse::balanceAsOf).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        return new AccountListResponse(responses, syncedAt);
    }

    /**
     * 별칭·기준 계좌를 바꾼다. 보낸 필드만 바꾼다.
     *
     * <ul>
     *   <li>없는 번호·남의 계좌·연결 해제한 계좌는 404 RESOURCE_NOT_FOUND</li>
     *   <li>isPrimary=true: 이 계좌를 기준 계좌로, 기존 기준 계좌는 푼다. 이미 기준 계좌면 그대로</li>
     *   <li>isPrimary=false: 기준 계좌를 풀면 기준 계좌가 없어지므로 400 PRIMARY_REQUIRED. 기준 계좌가 아니면 그대로</li>
     * </ul>
     * 같은 사용자의 기준 계좌 변경이 동시에 오면 사용자 행을 잠가 차례로 처리한다(마지막 요청이 남는다).
     */
    public AccountResponse update(AuthUser me, Long accountId, AccountUpdateRequest request) {
        LinkedAccount updated = transaction.execute(status -> {
            userRepository.findByIdForUpdate(me.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            LinkedAccount account = accountRepository.findByIdAndUserId(accountId, me.userId())
                    .filter(LinkedAccount::isConnected)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (Boolean.FALSE.equals(request.isPrimary()) && account.isPrimary()) {
                throw new BusinessException(ErrorCode.PRIMARY_REQUIRED);
            }
            if (request.accountName() != null) {
                account.rename(request.accountName().isBlank() ? null : request.accountName().strip());
            }
            if (Boolean.TRUE.equals(request.isPrimary()) && !account.isPrimary()) {
                makePrimary(me.userId(), account);
            }
            return accountRepository.saveAndFlush(account);
        });
        return responses(me.userId(), List.of(updated), false).get(0);
    }

    /** 기존 기준 계좌를 먼저 풀고 flush한 뒤 정한다. 순서를 바꾸면 UNIQUE(primary_owner_id)에 걸린다. */
    private void makePrimary(Long userId, LinkedAccount account) {
        List<LinkedAccount> previous = accountRepository.findByUserIdAndPrimaryTrue(userId);
        previous.forEach(a -> a.markPrimary(false));
        accountRepository.saveAllAndFlush(previous);
        account.markPrimary(true);
    }

    private List<AccountResponse> responses(Long userId, List<LinkedAccount> accounts, boolean refresh) {
        List<Long> ids = accounts.stream().map(LinkedAccount::getId).toList();
        Map<Long, AccountBalance> balances = balanceService.readAvailable(userId, accounts, refresh);
        Map<Long, List<AccountResponse.LinkedCard>> cards = activityReader.linkedCards(userId, ids);
        Map<Long, List<AccountResponse.UpcomingWithdrawal>> bills = activityReader.upcomingWithdrawals(userId, ids);
        return accounts.stream().map(account -> {
            AccountBalance balance = balances.get(account.getId());
            return new AccountResponse(account.getId(),
                    new BankResponse(account.getBankCode(), BankNames.of(account.getBankCode())),
                    account.getAccountAlias(), mask(account.getAccountLast4()),
                    balance == null ? null : balance.balance(), balance == null ? null : balance.fetchedAt(),
                    account.isPrimary(), cards.getOrDefault(account.getId(), List.of()),
                    bills.getOrDefault(account.getId(), List.of()),
                    account.getConnectedAt().truncatedTo(ChronoUnit.SECONDS));
        }).toList();
    }

    private static String mask(String last4) {
        return "****-****-****-" + (last4 == null ? "****" : last4);
    }
}
