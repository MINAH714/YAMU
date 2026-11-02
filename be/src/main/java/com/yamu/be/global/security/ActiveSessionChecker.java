package com.yamu.be.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Access Token이 아직 쓸 수 있는 세션인지 본다: 토큰의 기기가 해제(REVOKED)되지 않았고 주인이 활성(ACTIVE) 사용자.
 *
 * <p>Access Token(JWT)은 30분 동안 서버가 기억하지 않고 통과시키므로, 기기를 해제하거나 탈퇴해도 남은 토큰이 그대로 쓰였다.
 * 요청마다 기기 행 하나를 PK로 읽어 막는다(인덱스 조회 한 번). 잠금(LOCKED)은 로그인 실패 횟수 제한이라 이미 받은 토큰은 막지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ActiveSessionChecker {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean isActive(long userId, long userDeviceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_device d JOIN app_user u ON u.user_id = d.user_id
                WHERE d.user_device_id = :deviceId AND d.user_id = :userId
                  AND d.status <> 'REVOKED' AND u.status = 'ACTIVE'
                """, new MapSqlParameterSource("deviceId", userDeviceId).addValue("userId", userId), Integer.class);
        return count != null && count > 0;
    }
}
