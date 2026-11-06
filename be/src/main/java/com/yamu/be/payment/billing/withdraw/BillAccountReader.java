package com.yamu.be.payment.billing.withdraw;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.user.domain.AppUser;
import com.yamu.be.user.domain.AppUserRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 청구 출금(Task 5.4.3)에 쓸 금융망 접근 정보. 청구서에 저장한 출금 계좌(청구서 확정 당시 계좌)의 번호와 사용자의 SSAFY userKey를
 * 복호화한다. 앱에서 연결을 끊었어도(DISCONNECTED) 확정된 그 계좌로 출금한다(2026-11-05 결정). 은행에서 계좌가 없으면 출금이 거절되어
 * 미납이 된다. 계좌·사용자 표는 BE1 담당이라 읽기만 한다. 복호화 규칙은 {@code PaymentAccountReader}와 같다.
 *
 * <p>임시: 신용카드 환급 입금(Task 5.4.4)도 같은 규칙의 읽기를 따로 둔다. 둘이 develop에 들어가면 하나로 합친다.
 * 복호화한 값은 금융망 호출에만 쓰고 로그·응답·예외 메시지에 넣지 않는다.
 */
@Component
class BillAccountReader {

    private final LinkedAccountRepository accounts;
    private final AppUserRepository users;
    private final PersonalDataCipher cipher;

    BillAccountReader(LinkedAccountRepository accounts, AppUserRepository users, PersonalDataCipher cipher) {
        this.accounts = accounts;
        this.users = users;
        this.cipher = cipher;
    }

    /** 계좌가 이 사용자의 것이 아니거나 없으면, 또는 SSAFY 회원 키가 없으면 empty. */
    Optional<BankAccess> forBill(Long userId, Long linkedAccountId) {
        if (linkedAccountId == null) {
            return Optional.empty();
        }
        Optional<LinkedAccount> account = accounts.findByIdAndUserId(linkedAccountId, userId);
        Optional<AppUser> user = users.findById(userId).filter(found -> found.getSsafyUserKey() != null);
        if (account.isEmpty() || user.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BankAccess(
                cipher.decrypt(user.get().getSsafyUserKey(), CipherField.APP_USER_SSAFY_USER_KEY),
                cipher.decrypt(account.get().getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO)));
    }

    /** 금융망 호출에 쓰는 값. {@code toString}은 비밀값을 가린다. */
    record BankAccess(String userKey, String accountNo) {

        @Override
        public String toString() {
            return "BankAccess[***]";
        }
    }
}
