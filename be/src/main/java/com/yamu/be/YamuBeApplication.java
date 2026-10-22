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

    // 시간은 UTC로 통일한다. 개발 PC(KST)와 서버·운영 DB(UTC)가 같은 기준으로 저장하도록 JVM 기본값을 고정한다.
    // 실적 월·청구 마감일·영업일처럼 한국 날짜가 기준인 계산은 ZoneId.of("Asia/Seoul")을 명시해서 한다.
    @PostConstruct
    void setDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
