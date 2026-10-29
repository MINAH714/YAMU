package com.yamu.be.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, LoginRateLimitProperties.class, SignupProperties.class})
public class AuthConfig {
}
