package com.yamu.be.auth.dto;

/** @param expiresIn challenge 유효 시간(초) */
public record LoginChallengeResponse(String challengeId, String challenge, long expiresIn, boolean biometricEnabled) {
}
