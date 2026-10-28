package com.yamu.be.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 응답의 {@code user.onboardingCompleted}.
 *
 * <p><b>임시</b>: 명세는 "카드 1장 이상 + 기준 계좌 지정"인데 기준 계좌 저장 위치가 정해지지 않아,
 * "ACTIVE 카드 1장 이상 + 연결된 계좌 1개 이상"으로 판단한다. 카드·계좌 엔티티가 생기면 그쪽 Repository로 옮긴다.
 */
@Component
@RequiredArgsConstructor
public class OnboardingStatusReader {

    private final JdbcTemplate jdbc;

    public boolean isCompleted(Long userId) {
        Boolean completed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_card WHERE user_id = ? AND status = 'ACTIVE')
                   AND EXISTS (SELECT 1 FROM linked_account WHERE user_id = ? AND status = 'CONNECTED')
                """, Boolean.class, userId, userId);
        return Boolean.TRUE.equals(completed);
    }
}
