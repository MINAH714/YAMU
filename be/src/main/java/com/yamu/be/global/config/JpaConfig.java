package com.yamu.be.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * created_at·updated_at을 자동으로 채우는 JPA Auditing을 켠다.
 *
 * <p>{@code YamuBeApplication}에 붙이지 않고 따로 둔 이유: 거기 붙이면 웹 계층만 띄우는
 * {@code @WebMvcTest}에서도 JPA를 찾다가 "JPA metamodel must not be empty"로 테스트가 깨진다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
