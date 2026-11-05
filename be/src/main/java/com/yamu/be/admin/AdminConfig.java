package com.yamu.be.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 운영 API 설정과 {@code /api/admin/**} 키 확인 인터셉터 등록({@code PosConfig}와 같은 방식). */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig implements WebMvcConfigurer {

    static final String ADMIN_PATHS = "/api/admin/**";

    private final AdminProperties properties;

    public AdminConfig(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminKeyInterceptor(properties)).addPathPatterns(ADMIN_PATHS);
    }
}
