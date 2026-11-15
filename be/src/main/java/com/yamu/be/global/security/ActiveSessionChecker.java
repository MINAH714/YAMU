package com.yamu.be.global.security;

import com.yamu.be.auth.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Access Token이 아직 쓸 수 있는 세션인지 본다: 토큰의 기기가 해제(REVOKED)되지 않았고, 주인이 활성(ACTIVE) 사용자이고,
 * 토큰이 그 기기의 지금 로그인 세션에서 발급됐다.
 *
 * <p>Access Token(JWT)은 30분 동안 서버가 기억하지 않고 통과시키므로, 기기를 해제하거나 탈퇴하거나 로그아웃해도 남은 토큰이
 * 그대로 쓰였다. 요청마다 사용자·기기 행을 PK로 읽고 Redis의 기기 세션(Refresh Token 키)을 확인해 막는다. 잠금(LOCKED)은
 * 로그인 실패 횟수 제한이라 이미 받은 토큰은 막지 않는다.
 *
 * <p>막는 이유를 셋으로 나눈다. 다른 폰에서 재등록돼 해제된 기기는 앱이 기기 재등록으로 안내해야 해서
 * {@link Status#DEVICE_REVOKED}(S15P31E103-351), 탈퇴 등으로 사용자가 활성이 아니면 {@link Status#USER_INACTIVE},
 * 로그아웃했으면 {@link Status#LOGGED_OUT}(S15P31E103-466)이다. 탈퇴·기기 해제도 세션 키를 지우므로 DB를 먼저 봐야
 * 앞의 두 이유가 로그아웃으로 바뀌지 않는다.
 *
 * <p>세션이 있어도 토큰이 그 세션 시작(로그인)보다 먼저 발급됐으면 LOGGED_OUT이다. 그러지 않으면 로그아웃 뒤 같은 기기로 다시
 * 로그인했을 때 로그아웃 전에 받은 토큰이 되살아난다. 토큰의 발급 시각(iat)은 초 단위로 잘리고, 로그인은 Access Token을 세션보다
 * 먼저 만들기 때문에 {@link #ISSUED_AT_TOLERANCE}만큼 봐준다. 그래서 다시 로그인하기 2초 안쪽에 받은 토큰은 통과한다.
 *
 * <p>Redis가 끊기면 예외가 그대로 나가 500이 된다(DB가 끊겼을 때와 같다). 통과시키면 그동안 로그아웃한 토큰이 쓰인다.
 */
@Component
@RequiredArgsConstructor
public class ActiveSessionChecker {

    public enum Status { ACTIVE, DEVICE_REVOKED, USER_INACTIVE, LOGGED_OUT }

    static final Duration ISSUED_AT_TOLERANCE = Duration.ofSeconds(2);

    private final NamedParameterJdbcTemplate jdbc;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 사용자가 없거나 활성이 아니면 USER_INACTIVE가 먼저다. 사용자는 활성인데 기기가 없거나 해제됐으면 DEVICE_REVOKED.
     * 둘 다 통과했는데 기기 세션이 없거나 토큰이 세션보다 오래됐으면 LOGGED_OUT.
     *
     * @param issuedAt 토큰의 iat. 없으면 가장 오래된 토큰으로 본다
     */
    public Status status(long userId, long userDeviceId, Instant issuedAt) {
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
        Status status = found.isEmpty() ? Status.USER_INACTIVE : found.get(0);
        if (status != Status.ACTIVE) {
            return status;
        }
        Optional<Instant> sessionStartedAt = refreshTokenStore.sessionStartedAt(userDeviceId);
        Instant tokenIssuedAt = issuedAt == null ? Instant.EPOCH : issuedAt;
        boolean current = sessionStartedAt.isPresent()
                && !tokenIssuedAt.plus(ISSUED_AT_TOLERANCE).isBefore(sessionStartedAt.get());
        return current ? Status.ACTIVE : Status.LOGGED_OUT;
    }
}
