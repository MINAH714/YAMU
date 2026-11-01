package com.yamu.be.payment.barcode;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 결제 코드를 만든다. 형식은 숫자 16자리이고 바코드·QR·결제번호에 같은 값을 쓴다(DEC-003, FR-007).
 * 앞자리가 0일 수 있고 JavaScript Number의 안전 범위를 넘을 수 있으므로 항상 문자열로 다룬다.
 * 남이 추측해 결제하지 못하도록 {@link SecureRandom}을 쓴다(10^16가지, 약 53비트).
 */
@Component
class BarcodeTokenGenerator {

    private static final long BOUND = 10_000_000_000_000_000L;

    private final SecureRandom random = new SecureRandom();

    String next() {
        // 기본 Locale에 따라 다른 숫자 문자가 나오지 않도록 ROOT로 고정한다.
        return String.format(Locale.ROOT, "%016d", random.nextLong(BOUND));
    }
}
