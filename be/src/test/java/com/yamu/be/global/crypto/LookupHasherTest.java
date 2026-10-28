package com.yamu.be.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LookupHasherTest {

    private final LookupHasher hasher = new LookupHasher(CryptoTestKeys.keys());

    @Test
    void 같은_번호는_표기가_달라도_같은_32바이트_해시가_나온다() {
        byte[] plain = hasher.phone("01012345678");

        assertThat(plain).hasSize(32);
        assertThat(hasher.phone("010-1234-5678")).isEqualTo(plain);
        assertThat(hasher.phone("+82 10 1234 5678")).isEqualTo(plain);
    }

    @Test
    void 번호가_한_자리만_달라도_해시가_다르다() {
        assertThat(hasher.phone("01012345678")).isNotEqualTo(hasher.phone("01012345679"));
    }

    @Test
    void 키가_다르면_같은_번호도_다른_해시가_나온다() {
        LookupHasher otherKey = new LookupHasher(new CryptoKeys(new CryptoProperties(1,
                Map.of(1, CryptoTestKeys.DATA_KEY_1), CryptoTestKeys.DATA_KEY_2, CryptoTestKeys.PIN_PEPPER)));

        assertThat(otherKey.phone("01012345678")).isNotEqualTo(hasher.phone("01012345678"));
    }

    @Test
    void 계좌는_은행이_다르면_같은_번호라도_다른_해시다() {
        assertThat(hasher.account("088", "088-822-4489693615")).isEqualTo(hasher.account("088", "0888224489693615"));
        assertThat(hasher.account("088", "0888224489693615")).isNotEqualTo(hasher.account("004", "0888224489693615"));
    }

    @Test
    void 카드번호는_하이픈과_공백을_무시한다() {
        assertThat(hasher.card("9410 1234-1234 5678")).isEqualTo(hasher.card("9410123412345678"));
    }
}
