package com.yamu.be.auth.dto;

/** @param expiresIn Access Token 유효 시간(초) */
public record LoginResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, LoginUser user) {

    /** @param onboardingCompleted false면 앱이 초기 설정 화면으로 이동한다 */
    public record LoginUser(Long userId, String name, boolean onboardingCompleted) {
    }
}
