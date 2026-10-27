package com.yamu.be.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalDataCipherTest {

    private final PersonalDataCipher cipher = new PersonalDataCipher(CryptoTestKeys.keys(), new SecureRandom());

    @Test
    void 암호화한_값을_같은_컬럼으로_복호화하면_원래_값이다() {
        byte[] envelope = cipher.encrypt("01012345678", CipherField.APP_USER_PHONE);

        assertThat(cipher.decrypt(envelope, CipherField.APP_USER_PHONE)).isEqualTo("01012345678");
    }

    @Test
    void 봉투는_버전_키번호_nonce_암호문_tag_순서이고_길이는_30_더하기_평문이다() {
        byte[] envelope = cipher.encrypt("01012345678", CipherField.APP_USER_PHONE);

        assertThat(envelope).hasSize(30 + 11);
        assertThat(envelope[0]).isEqualTo(PersonalDataCipher.VERSION);
        assertThat(envelope[1]).isEqualTo((byte) 1);
    }

    @Test
    void 같은_값도_암호화할_때마다_다른_봉투가_나온다() {
        byte[] first = cipher.encrypt("01012345678", CipherField.APP_USER_PHONE);
        byte[] second = cipher.encrypt("01012345678", CipherField.APP_USER_PHONE);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 암호문을_1비트라도_바꾸면_복호화가_실패한다() {
        byte[] envelope = cipher.encrypt("0888224489693615", CipherField.LINKED_ACCOUNT_NO);
        envelope[20] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(envelope, CipherField.LINKED_ACCOUNT_NO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 다른_컬럼의_암호문을_옮겨_심으면_복호화가_실패한다() {
        byte[] accountEnvelope = cipher.encrypt("0888224489693615", CipherField.LINKED_ACCOUNT_NO);

        assertThatThrownBy(() -> cipher.decrypt(accountEnvelope, CipherField.APP_USER_PHONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키를_바꿔도_옛_키번호로_잠근_봉투는_열린다() {
        byte[] oldEnvelope = cipher.encrypt("9410123412345678", CipherField.USER_CARD_NO);
        PersonalDataCipher rotated = new PersonalDataCipher(new CryptoKeys(new CryptoProperties(2,
                Map.of(1, CryptoTestKeys.DATA_KEY_1, 2, CryptoTestKeys.DATA_KEY_2),
                CryptoTestKeys.HMAC_KEY, CryptoTestKeys.PIN_PEPPER)), new SecureRandom());

        byte[] newEnvelope = rotated.encrypt("9410123412345678", CipherField.USER_CARD_NO);

        assertThat(newEnvelope[1]).isEqualTo((byte) 2);
        assertThat(rotated.decrypt(oldEnvelope, CipherField.USER_CARD_NO)).isEqualTo("9410123412345678");
        assertThat(rotated.decrypt(newEnvelope, CipherField.USER_CARD_NO)).isEqualTo("9410123412345678");
    }

    @Test
    void 설정에서_지운_키번호의_봉투는_열지_못한다() {
        PersonalDataCipher rotated = new PersonalDataCipher(new CryptoKeys(new CryptoProperties(2,
                Map.of(2, CryptoTestKeys.DATA_KEY_2), CryptoTestKeys.HMAC_KEY, CryptoTestKeys.PIN_PEPPER)),
                new SecureRandom());
        byte[] oldEnvelope = cipher.encrypt("01012345678", CipherField.APP_USER_PHONE);

        assertThatThrownBy(() -> rotated.decrypt(oldEnvelope, CipherField.APP_USER_PHONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1");
    }

    @Test
    void 컬럼_길이_2048바이트를_넘는_값은_거절한다() {
        String tooLong = "a".repeat(PersonalDataCipher.MAX_ENVELOPE_BYTES - PersonalDataCipher.OVERHEAD_BYTES + 1);
        String fits = "a".repeat(PersonalDataCipher.MAX_ENVELOPE_BYTES - PersonalDataCipher.OVERHEAD_BYTES);

        assertThat(cipher.encrypt(fits, CipherField.APP_USER_SSAFY_USER_KEY)).hasSize(PersonalDataCipher.MAX_ENVELOPE_BYTES);
        assertThatThrownBy(() -> cipher.encrypt(tooLong, CipherField.APP_USER_SSAFY_USER_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 형식이_틀린_봉투는_거절한다() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[10], CipherField.APP_USER_PHONE))
                .isInstanceOf(IllegalStateException.class);
        byte[] wrongVersion = cipher.encrypt("01012345678", CipherField.APP_USER_PHONE);
        wrongVersion[0] = 9;
        assertThatThrownBy(() -> cipher.decrypt(wrongVersion, CipherField.APP_USER_PHONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("버전");
    }

    @Test
    void 한글도_UTF8로_암호화된다() {
        byte[] envelope = cipher.encrypt("김야무", CipherField.APP_USER_SSAFY_USER_KEY);

        assertThat(envelope).hasSize(30 + 9);
        assertThat(cipher.decrypt(envelope, CipherField.APP_USER_SSAFY_USER_KEY)).isEqualTo("김야무");
    }
}
