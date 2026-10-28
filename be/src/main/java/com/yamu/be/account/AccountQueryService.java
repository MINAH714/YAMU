package com.yamu.be.account;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 기능(예정 지출·결제)이 "요청한 사용자의 연결된 계좌인지" 확인할 때 쓴다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountQueryService {

    private final LinkedAccountRepository accountRepository;

    /**
     * 사용자 본인 소유이고 연결 상태(CONNECTED)인 계좌를 돌려준다.
     * 없는 번호·남의 계좌·연결 해제된 계좌는 모두 404 RESOURCE_NOT_FOUND다. 남의 계좌가 있는지 여부를 드러내지 않는다.
     */
    public LinkedAccountRef requireConnectedAccount(Long userId, Long linkedAccountId) {
        if (userId == null || linkedAccountId == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        LinkedAccount account = accountRepository.findByIdAndUserId(linkedAccountId, userId)
                .filter(LinkedAccount::isConnected)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new LinkedAccountRef(account.getId(), account.getBankCode(), account.getAccountLast4());
    }
}
