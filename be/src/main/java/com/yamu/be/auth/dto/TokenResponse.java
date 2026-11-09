package com.yamu.be.auth.dto;

/** @param expiresIn Access Token 유효 시간(초) */
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
