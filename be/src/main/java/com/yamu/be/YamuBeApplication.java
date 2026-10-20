package com.yamu.be;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class YamuBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(YamuBeApplication.class, args);
    }

    // 서버(Docker 등)의 기본 시간대가 UTC여도 LocalDateTime.now()가 한국 시간이 되도록 고정한다.
    @PostConstruct
    void setDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }
}
