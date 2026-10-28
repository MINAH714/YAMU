package com.yamu.be.global.security;

/**
 * 요청한 사용자. Access Token(JWT)의 {@code sub}(userId)와 {@code did}(userDeviceId)에서 만든다.
 *
 * <pre>
 * &#64;GetMapping("/cards")
 * ApiResponse&lt;...&gt; cards(&#64;AuthenticationPrincipal AuthUser me) { return ...findMine(me.userId()); }
 * </pre>
 *
 * 다른 사용자 데이터 접근은 Service에서 {@code me.userId()}를 조회 조건에 넣어 404로 막는다(명세 1장).
 */
public record AuthUser(Long userId, Long userDeviceId) {
}
