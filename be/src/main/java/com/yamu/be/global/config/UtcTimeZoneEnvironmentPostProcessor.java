package com.yamu.be.global.config;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 시간은 UTC로 통일한다. 개발 PC(KST)와 서버·운영 DB(UTC)가 같은 기준으로 저장하도록 JVM 기본값을 고정한다.
 * 실적 월·청구 마감일·영업일처럼 한국 날짜가 기준인 계산은 ZoneId.of("Asia/Seoul")을 명시해서 한다.
 *
 * <p>빈을 만들기 전(설정 파일을 읽는 단계)에 바꾼다. MySQL 드라이버는 연결을 만들 때의 JVM 시간대를 연결에 저장해
 * DATE를 읽을 때 쓰므로, 연결 풀이 열린 뒤에 바꾸면 KST로 만든 연결이 날짜를 하루 이르게 읽는다(S15P31E103-179).
 * main 실행과 테스트(@SpringBootTest) 모두 이 단계를 거친다. 등록은 META-INF/spring.factories.
 */
public class UtcTimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
