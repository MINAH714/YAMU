package com.yamu.be.payment.approve;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.account.domain.LinkedAccountStatus;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 결제 출금에 필요한 금융망 접근 정보. 카드의 연결 계좌 번호와 사용자의 SSAFY userKey를 복호화해 돌려준다.
 * 계좌·사용자 엔티티는 BE1 담당이라 읽기만 하고, 복호화 규칙은 {@code AccountLinkService}와 같다(CipherField).
 *
 * <p>복호화한 값은 금융망 호출에만 쓰고 로그·응답·예외 메시지에 넣지 않는다.
 */
@Component
public class PaymentAccountReader {

    private final CardQueryService cardQuery;
    private final LinkedAccountRepository accounts;
    private final AppUserRepository users;
    private final PersonalDataCipher cipher;

    public PaymentAccountReader(CardQueryService cardQuery, LinkedAccountRepository accounts, AppUserRepository users,
            PersonalDataCipher cipher) {
        this.cardQuery = cardQuery;
        this.accounts = accounts;
        this.users = users;
        this.cipher = cipher;
    }

    /**
     * 이 사용자의 카드로 출금할 계좌와 키. 카드가 등록돼 있지 않거나(해제·남의 카드), 연결 계좌가 없거나 끊겼거나,
     * SSAFY 회원 키가 없으면 empty다. 받는 쪽은 결제 불가(ACCOUNT_UNAVAILABLE)로 다룬다.
     */
    public Optional<BankAccess> forCard(Long userId, Long userCardId) {
        Optional<UserCardRef> card = cardQuery.findRegisteredCard(userId, userCardId);
        if (card.isEmpty() || card.get().linkedAccountId() == null) {
            return Optional.empty();
        }
        Optional<LinkedAccount> account = accounts.findByIdAndUserId(card.get().linkedAccountId(), userId)
                .filter(found -> found.getStatus() == LinkedAccountStatus.CONNECTED);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        Optional<AppUser> user = users.findById(userId).filter(found -> found.getSsafyUserKey() != null);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BankAccess(
                cipher.decrypt(user.get().getSsafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY),
                cipher.decrypt(account.get().getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO),
                card.get().cardType(), account.get().getId()));
    }

    /**
     * 금융망 호출에 쓰는 값. {@code toString}은 비밀값을 가린다.
     *
     * @param cardType 행의 카드 종류. 복구가 체크카드 결제에만 거래내역을 찾도록 한다
     */
    public record BankAccess(String userKey, String accountNo, CardType cardType, Long linkedAccountId) {

        @Override
        public String toString() {
            return "BankAccess[cardType=" + cardType + ", linkedAccountId=" + linkedAccountId + "]";
        }
    }
}
