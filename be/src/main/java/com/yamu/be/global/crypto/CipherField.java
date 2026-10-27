package com.yamu.be.global.crypto;

/**
 * 암호화하는 컬럼. 각 값은 AES-GCM의 AAD로 들어가 암호문을 그 컬럼에 묶는다.
 *
 * <p>AAD가 다르면 복호화가 실패하므로, DB에서 계좌 암호문을 휴대폰 컬럼에 옮겨 심어도 열리지 않는다.
 * 저장된 데이터가 이 문자열에 묶이므로 한 번 쓰기 시작한 값은 바꾸지 않는다.
 *
 * <p>행(user_id·PK)까지는 묶지 않는다. 새 사용자는 INSERT 전에 PK가 없고, DB 쓰기 권한이 있는 사람은 암호문을 옮기지 않아도
 * user_id 같은 FK를 직접 바꿀 수 있어서 행 묶기로 막아지는 위협이 거의 없기 때문이다. DB 쓰기 권한 보호는 계정·네트워크로 한다.
 */
public enum CipherField {

    APP_USER_PHONE("app_user.phone"),
    APP_USER_SSAFY_USER_KEY("app_user.ssafy_user_key"),
    LINKED_ACCOUNT_NO("linked_account.account_no"),
    USER_CARD_NO("user_card.card_no");

    private final String aad;

    CipherField(String aad) {
        this.aad = aad;
    }

    String aad() {
        return aad;
    }
}
