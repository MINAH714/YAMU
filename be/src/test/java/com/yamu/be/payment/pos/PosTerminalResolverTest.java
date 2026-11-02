package com.yamu.be.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.domain.FacilityType;
import com.yamu.be.payment.domain.PaymentChannel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PosTerminalResolverTest {

    static final PosProperties.Terminal CAFE = new PosProperties.Terminal("POS-TEST-CAFE", "TEST_CAFE",
            PaymentChannel.OFFLINE, FacilityType.GENERAL);

    @Test
    void 키가_맞고_posCode가_설정에_있으면_그_시나리오를_돌려준다() {
        PosTerminalResolver resolver = resolver("secret");

        assertThat(resolver.resolve("secret", "POS-TEST-CAFE")).isEqualTo(CAFE);
    }

    @Test
    void 키가_없거나_다르면_INVALID_POS_KEY() {
        PosTerminalResolver resolver = resolver("secret");

        assertThatThrownBy(() -> resolver.resolve(null, "POS-TEST-CAFE")).satisfies(code(ErrorCode.INVALID_POS_KEY));
        assertThatThrownBy(() -> resolver.resolve("wrong", "POS-TEST-CAFE")).satisfies(code(ErrorCode.INVALID_POS_KEY));
        assertThatThrownBy(() -> resolver.resolve("secret ", "POS-TEST-CAFE")).satisfies(code(ErrorCode.INVALID_POS_KEY));
    }

    @Test
    void 설정에_키가_비어_있으면_어떤_키로도_쓸_수_없다() {
        PosTerminalResolver resolver = resolver("");

        assertThatThrownBy(() -> resolver.resolve("", "POS-TEST-CAFE")).satisfies(code(ErrorCode.INVALID_POS_KEY));
    }

    @Test
    void 키는_맞지만_posCode가_설정에_없으면_MERCHANT_NOT_FOUND() {
        PosTerminalResolver resolver = resolver("secret");

        assertThatThrownBy(() -> resolver.resolve("secret", "POS-UNKNOWN")).satisfies(code(ErrorCode.MERCHANT_NOT_FOUND));
    }

    private static PosTerminalResolver resolver(String key) {
        return new PosTerminalResolver(new PosProperties(key, Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofSeconds(15), List.of(CAFE)));
    }

    private static java.util.function.Consumer<Throwable> code(ErrorCode expected) {
        return e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(expected);
    }
}
