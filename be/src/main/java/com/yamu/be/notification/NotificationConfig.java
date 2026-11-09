package com.yamu.be.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfig {
}
