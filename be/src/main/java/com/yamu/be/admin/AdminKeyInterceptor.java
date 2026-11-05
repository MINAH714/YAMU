package com.yamu.be.admin;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/admin/**} 전체의 {@code X-Admin-Key}를 한 곳에서 확인한다({@link AdminConfig}가 등록). 보안 설정은 운영 API를
 * Bearer 검사 없이 열어 두므로(명세 1장: 운영 API는 X-Admin-Key 인증) 새 운영 API를 추가해도 여기서 막힌다.
 * 키가 없거나 다르거나, 서버에 키가 설정되지 않았으면 403 {@code ADMIN_ONLY}. 비교는 {@link MessageDigest#isEqual}로 한다.
 *
 * <p>한계: 키 하나로 모든 운영 API를 연다. 호출 횟수 제한·감사 기록은 없다(실행 결과는 서버 로그에 남는다).
 */
class AdminKeyInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Key";

    private final AdminProperties properties;

    AdminKeyInterceptor(AdminProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String given = request.getHeader(HEADER);
        if (!properties.hasKey() || given == null || !MessageDigest.isEqual(
                properties.key().getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return true;
    }
}
