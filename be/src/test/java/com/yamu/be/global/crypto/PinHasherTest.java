package com.yamu.be.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PinHasherTest {

    private final PinHasher hasher = new PinHasher(CryptoTestKeys.keys());

    @Test
    void 해시는_BCrypt_형식이고_같은_PIN만_맞는다() {
        String hash = hasher.hash("482913");

        assertThat(hash).startsWith("$2a$10$").hasSize(60);
        assertThat(hasher.matches("482913", hash)).isTrue();
        assertThat(hasher.matches("482914", hash)).isFalse();
    }

    @Test
    void 같은_PIN도_솔트_때문에_해시가_매번_다르다() {
        assertThat(hasher.hash("482913")).isNotEqualTo(hasher.hash("482913"));
    }

    @Test
    void pepper가_다르면_같은_PIN도_맞지_않는다() {
        PinHasher otherPepper = new PinHasher(new CryptoKeys(new CryptoProperties(1,
                Map.of(1, CryptoTestKeys.DATA_KEY_1), CryptoTestKeys.HMAC_KEY, CryptoTestKeys.DATA_KEY_2)));

        assertThat(otherPepper.matches("482913", hasher.hash("482913"))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "12345", "1234567", "12345a", "１２３４５６"})
    void 숫자_6자리가_아니면_해시하지_않고_검증도_실패한다(String pin) {
        assertThatThrownBy(() -> hasher.hash(pin)).isInstanceOf(BusinessException.class);
        assertThat(hasher.matches(pin, hasher.hash("482913"))).isFalse();
    }

    @Test
    void 저장된_해시가_없으면_실패한다() {
        assertThat(hasher.matches("482913", null)).isFalse();
        assertThat(hasher.matches("482913", "")).isFalse();
    }
}
