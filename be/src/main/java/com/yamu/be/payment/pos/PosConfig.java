package com.yamu.be.payment.pos;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 모의 POS 설정과 {@code /api/pos/**} 키 확인 인터셉터 등록.
 *
 * <p>{@code @WebMvcTest} 같은 웹 계층 테스트는 WebMvcConfigurer를 모두 올리지만 {@link PosTerminalResolver}는 올리지 않는다.
 * 그래서 키 확인 빈은 있을 때만 받아 등록한다(서버에서는 항상 있다).
 */
@Configuration
@EnableConfigurationProperties(PosProperties.class)
public class PosConfig implements WebMvcConfigurer {

    static final String POS_PATHS = "/api/pos/**";

    private final ObjectProvider<PosTerminalResolver> terminals;

    public PosConfig(ObjectProvider<PosTerminalResolver> terminals) {
        this.terminals = terminals;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        terminals.ifAvailable(resolver ->
                registry.addInterceptor(new PosKeyInterceptor(resolver)).addPathPatterns(POS_PATHS));
    }
}
