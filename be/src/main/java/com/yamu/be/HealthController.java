package com.yamu.be;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버 생존 확인용. FE 연결 확인과 배포 헬스체크가 {"status":"ok"}를 그대로 읽으므로
 * 공통 응답(ApiResponse)으로 감싸지 않는 유일한 예외다.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
