package com.yamu.be.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/** 실제 logback-spring.xml로 띄운 로그 출력에서도 가려지는지 본다. Boot가 로그 설정을 읽게 빈 컨텍스트를 띄운다. */
@SpringBootTest(classes = LogMaskingOutputTest.Empty.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.banner-mode=off")
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingOutputTest {

    private static final Logger log = LoggerFactory.getLogger(LogMaskingOutputTest.class);

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    @Test
    void 로그_출력에는_가린_값만_남고_형식은_그대로다(CapturedOutput output) {
        log.warn("가림 확인 phone={} card={}", "010-1234-5678", "1234567812345678");

        assertThat(output.getOut()).contains("가림 확인 phone=010-****-5678 card=************5678")
                .doesNotContain("1234-5678", "1234567812345678")
                .contains("WARN").contains("logging.LogMaskingOutputTest");
    }
}
