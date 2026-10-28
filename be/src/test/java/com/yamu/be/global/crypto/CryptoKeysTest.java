package com.yamu.be.global.crypto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CryptoKeysTest {

    private static final String SHORT_KEY = "c2hvcnQ="; // "short", 5바이트

    @Test
    void 올바른_키면_만들어진다() {
        assertThatCode(CryptoTestKeys::keys).doesNotThrowAnyException();
    }

    @Test
    void 운영에_환경변수를_빠뜨리면_서버_시작_때_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, ""), "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비어 있습니다");
    }

    @Test
    void 데이터_키가_32바이트가_아니면_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, SHORT_KEY),
                CryptoTestKeys.HMAC_KEY, CryptoTestKeys.PIN_PEPPER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    void 현재_키번호의_키가_없으면_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(2, Map.of(1, CryptoTestKeys.DATA_KEY_1),
                CryptoTestKeys.HMAC_KEY, CryptoTestKeys.PIN_PEPPER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data-key-id");
    }

    @Test
    void HMAC_키와_pepper가_짧으면_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, CryptoTestKeys.DATA_KEY_1),
                SHORT_KEY, CryptoTestKeys.PIN_PEPPER)))
                .hasMessageContaining("hmac-key");
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, CryptoTestKeys.DATA_KEY_1),
                CryptoTestKeys.HMAC_KEY, SHORT_KEY)))
                .hasMessageContaining("pin-pepper");
    }

    @Test
    void 용도가_다른_키에_같은_값을_넣으면_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, CryptoTestKeys.DATA_KEY_1),
                CryptoTestKeys.DATA_KEY_1, CryptoTestKeys.PIN_PEPPER)))
                .hasMessageContaining("서로 달라야");
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, CryptoTestKeys.DATA_KEY_1),
                CryptoTestKeys.HMAC_KEY, CryptoTestKeys.HMAC_KEY)))
                .hasMessageContaining("서로 달라야");
    }

    @Test
    void Base64가_아니면_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(1, Map.of(1, "!!!not-base64!!!"),
                CryptoTestKeys.HMAC_KEY, CryptoTestKeys.PIN_PEPPER)))
                .hasMessageContaining("Base64");
    }

    @Test
    void 키번호가_범위를_벗어나면_실패한다() {
        assertThatThrownBy(() -> new CryptoKeys(new CryptoProperties(256, Map.of(256, CryptoTestKeys.DATA_KEY_1),
                CryptoTestKeys.HMAC_KEY, CryptoTestKeys.PIN_PEPPER)))
                .hasMessageContaining("1~255");
    }
}
