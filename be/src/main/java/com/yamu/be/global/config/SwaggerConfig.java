package com.yamu.be.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 설정. 서버 실행 후 /swagger-ui.html 에서 확인한다.
 *
 * <p>JWT 인증 입력칸(Authorize 버튼)은 인증 방식이 정해지면 여기에 추가한다.
 * 운영 서버에서 문서를 열어둘지도 보안 결정과 함께 정한다.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("YAMU API")
                        .description("결제는 간편하게, 혜택은 야무지게. YAMU 백엔드 API 문서")
                        .version("v1"));
    }
}
