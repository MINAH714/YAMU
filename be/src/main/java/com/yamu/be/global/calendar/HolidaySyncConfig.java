package com.yamu.be.global.calendar;

import com.yamu.be.global.redis.RedisLocks;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestClient;

/** 공휴일 API 동기화 구성(docs/be/holiday.md). 정기 작업은 {@code SchedulingConfig}가 켠다. */
@Configuration
@EnableConfigurationProperties(HolidayApiProperties.class)
class HolidaySyncConfig {

    @Bean
    HolidayApiClient holidayApiClient(RestClient.Builder builder, HolidayApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return new HolidayApiClient(builder.requestFactory(requestFactory).build(), properties);
    }

    @Bean
    RedisHolidayStore redisHolidayStore(StringRedisTemplate redis) {
        return new RedisHolidayStore(redis);
    }

    @Bean
    HolidaySyncJob holidaySyncJob(HolidayApiClient client, RedisHolidayStore store, MergedHolidays holidays,
            RedisLocks redisLocks, HolidayApiProperties properties, TaskScheduler scheduler, Clock clock) {
        return new HolidaySyncJob(client, store, holidays, redisLocks, properties, scheduler, clock);
    }
}
