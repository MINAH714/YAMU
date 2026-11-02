package com.yamu.be.card;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CardRegisterProperties.class, CardImageProperties.class})
public class CardConfig {
}
