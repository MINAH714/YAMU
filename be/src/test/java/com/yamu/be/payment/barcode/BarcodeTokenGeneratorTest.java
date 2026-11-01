package com.yamu.be.payment.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BarcodeTokenGeneratorTest {

    @Test
    void 숫자_16자리_코드를_만들고_앞자리_0도_그대로_둔다() {
        BarcodeTokenGenerator generator = new BarcodeTokenGenerator();
        Set<String> codes = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String code = generator.next();
            assertThat(code).matches("[0-9]{16}");
            codes.add(code);
        }
        // 10^16가지 중 1,000개라 겹칠 일이 사실상 없다. 겹치면 난수 생성이 잘못된 것이다.
        assertThat(codes).hasSize(1_000);
    }
}
