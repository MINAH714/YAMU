package com.yamu.be.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.yamu.be.global.security.AuthUser;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * MockMvc 요청에 로그인 사용자를 넣는다. 컨트롤러의 {@code @AuthenticationPrincipal AuthUser me}로 그대로 들어간다.
 *
 * <pre>
 * mockMvc.perform(get("/api/cashflow").with(AuthUsers.asUser(1L)))
 * </pre>
 *
 * <p>{@code jwt()}는 principal이 {@link AuthUser}가 아니라서 {@code me}가 null이 된다. 이 유틸을 쓴다.
 */
public final class AuthUsers {

    private static final long DEFAULT_DEVICE_ID = 1L;

    private AuthUsers() {
    }

    public static RequestPostProcessor asUser(long userId) {
        return asUser(userId, DEFAULT_DEVICE_ID);
    }

    public static RequestPostProcessor asUser(long userId, long userDeviceId) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthUser(userId, userDeviceId), null, List.of()));
    }
}
