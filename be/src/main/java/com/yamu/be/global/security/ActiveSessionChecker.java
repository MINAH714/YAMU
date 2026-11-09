package com.yamu.be.global.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Access Token이 아직 쓸 수 있는 세션인지 본다: 토큰의 기기가 해제(REVOKED)되지 않았고 주인이 활성(ACTIVE) 사용자.
 *
 * <p>Access Token(JWT)은 30분 동안 서버가 기억하지 않고 통과시키므로, 기기를 해제하거나 탈퇴해도 남은 토큰이 그대로 쓰였다.
 * 요청마다 사용자·기기 행을 PK로 읽어 막는다. 잠금(LOCKED)은 로그인 실패 횟수 제한이라 이미 받은 토큰은 막지 않는다.
 *
 * <p>막는 이유를 둘로 나눈다(S15P31E103-351). 다른 폰에서 재등록돼 해제된 기기는 앱이 기기 재등록으로 안내해야 해서
 * {@link Status#DEVICE_REVOKED}, 탈퇴 등으로 사용자가 활성이 아니면 {@link Status#USER_INACTIVE}다.
 */
@Component
@RequiredArgsConstructor
public class ActiveSessionChecker {

    public enum Status { ACTIVE, DEVICE_REVOKED, USER_INACTIVE }

    private final NamedParameterJdbcTemplate jdbc;

    /** 사용자가 없거나 활성이 아니면 USER_INACTIVE가 먼저다. 사용자는 활성인데 기기가 없거나 해제됐으면 DEVICE_REVOKED. */
    public Status status(long userId, long userDeviceId) {
        List<Status> found = jdbc.query("""
                SELECT u.status AS user_status, d.status AS device_status
                  FROM app_user u
                  LEFT JOIN user_device d ON d.user_device_id = :deviceId AND d.user_id = u.user_id
                 WHERE u.user_id = :userId
                """, new MapSqlParameterSource("deviceId", userDeviceId).addValue("userId", userId),
                (rs, n) -> {
                    if (!"ACTIVE".equals(rs.getString("user_status"))) {
                        return Status.USER_INACTIVE;
                    }
                    String device = rs.getString("device_status");
                    return device == null || "REVOKED".equals(device) ? Status.DEVICE_REVOKED : Status.ACTIVE;
                });
        return found.isEmpty() ? Status.USER_INACTIVE : found.get(0);
    }
}
