package com.yamu.be.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.support.MySqlTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 웹 서버 없이 띄우는 실행(가입 시연 페르소나 시드 {@code persona-seed} 프로필, {@code web-application-type: none})에서도
 * 앱이 뜨는지 확인한다. 보안 설정은 웹 요청에만 필요하므로 이때는 만들지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {"spring.main.web-application-type=none",
        "spring.application.name=yamu-non-web-startup-test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NonWebStartupIntegrationTest extends MySqlTestSupport {

    @Autowired ApplicationContext context;

    @Test
    void 웹_서버_없이_띄우면_보안_필터_없이_앱이_뜬다() {
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
