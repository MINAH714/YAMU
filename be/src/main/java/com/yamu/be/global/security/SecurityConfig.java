package com.yamu.be.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security 설정. REST API라 세션·CSRF·폼 로그인을 쓰지 않고, 요청마다 Bearer JWT로 사용자를 확인한다.
 *
 * <p>JWT 검증 필터는 Spring Resource Server가 붙인다. 직접 짠 것은 토큰 → {@link AuthUser} 변환과 401 응답 모양뿐이다.
 * 공개 URL 외에는 모두 인증이 필요하다. 인증 없이 열어야 하는 API가 생기면 {@link #PUBLIC_POST}에 추가한다.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 로그인 전에 부르는 API. 가입 API가 생기면 여기에 추가한다.
     * 회원가입은 Bearer로 가입 진행 토큰(JWT 아님)을 받으므로 여기 두어 JWT 검사를 건너뛰고 컨트롤러가 직접 확인한다.
     */
    static final String[] PUBLIC_POST = {
            "/api/auth/login/challenge",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/phone-verifications",
            "/api/auth/phone-verifications/{verificationId}/confirm"
    };

    /**
     * Access Token과 가입 진행 토큰(signupToken)을 둘 다 받는 API. 가입 중에도 계좌 연결·PIN 설정·기기 등록을 해야 해서다.
     * 인증 없이 열어 두고 컨트롤러가 확인한다. Access Token이 오면 아래 resolver가 넘겨 JWT 검사를 하고,
     * 가입 진행 토큰("sgt_…")이 오면 JWT 검사를 건너뛴다(JWT가 아니라 그대로 두면 401이 난다).
     */
    static final String[] SIGNUP_TOKEN_POST = {
            "/api/accounts/one-won-auth",
            "/api/accounts",
            "/api/auth/pin",
            "/api/auth/devices"
    };

    static final String[] SIGNUP_TOKEN_GET = {
            "/api/accounts/candidates"
    };

    private static final String SIGNUP_TOKEN_PREFIX = "sgt_";

    private static final String[] PUBLIC_GET = {
            "/api/health",
            "/actuator/health", "/actuator/health/**",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
    };

    private final AuthUserJwtConverter authUserJwtConverter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(HttpMethod.POST, SIGNUP_TOKEN_POST).permitAll()
                        .requestMatchers(HttpMethod.GET, SIGNUP_TOKEN_GET).permitAll()
                        // 컨트롤러가 던진 예외를 Spring이 /error로 다시 보낼 때 401로 바뀌지 않게 연다.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authUserJwtConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint));
        return http.build();
    }

    /**
     * 공개 URL에서는 Authorization 헤더를 읽지 않는다.
     * 기본 동작은 공개 URL이어도 헤더가 있으면 토큰을 검사해서, 만료된 Access Token을 붙인 채 /api/auth/refresh를 부르면
     * 401 TOKEN_EXPIRED가 나고 앱이 재발급 → 401을 무한 반복한다. 헬스 체크도 만료 토큰 때문에 401이 나지 않게 같이 뺀다.
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher publicPaths = new OrRequestMatcher(Stream.concat(
                Arrays.stream(PUBLIC_POST).map(path -> (RequestMatcher) paths.matcher(HttpMethod.POST, path)),
                Arrays.stream(PUBLIC_GET).map(path -> (RequestMatcher) paths.matcher(HttpMethod.GET, path))).toList());
        // 가입 진행 토큰을 JWT 검사에서 빼는 것은 그 토큰을 받는 API에서만. 다른 경로에서는 JWT로 검사해 401이 된다.
        RequestMatcher signupTokenPaths = new OrRequestMatcher(Stream.concat(
                Arrays.stream(SIGNUP_TOKEN_POST).map(path -> (RequestMatcher) paths.matcher(HttpMethod.POST, path)),
                Arrays.stream(SIGNUP_TOKEN_GET).map(path -> (RequestMatcher) paths.matcher(HttpMethod.GET, path))).toList());
        return (HttpServletRequest request) -> {
            if (publicPaths.matches(request)) {
                return null;
            }
            String token = delegate.resolve(request);
            boolean signupToken = token != null && token.startsWith(SIGNUP_TOKEN_PREFIX);
            return signupToken && signupTokenPaths.matches(request) ? null : token;
        };
    }
}
