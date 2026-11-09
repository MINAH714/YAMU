package com.yamu.be.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 응답의 {@code user.onboardingCompleted}. 명세 「로그인」 기준 "카드 1장 이상 등록 + 기준 계좌 지정"이다.
 *
 * <ul>
 *   <li>등록된 카드: ACTIVE·INACTIVE({@code CardRegistrationStatus.isRegistered()}와 같은 기준). INACTIVE는 추천에서만 뺀 카드라
 *       등록은 되어 있다. ACTIVE만 세면 마지막 ACTIVE 카드를 끈 사용자가 다시 로그인했을 때 카드 등록 화면으로 가고, 남은 카드를
 *       등록하려 하면 CARD_ALREADY_REGISTERED가 난다. 결제는 ACTIVE 카드가 0장이면 바코드 발급에서 따로 막는다(NO_ACTIVE_CARD).</li>
 *   <li>기준 계좌: 연결된(CONNECTED) 계좌 중 is_primary. 계좌를 연결하면 첫 계좌가 기준이 되고 기준을 풀 수 없어(PRIMARY_REQUIRED)
 *       연결 계좌가 있으면 늘 하나 있다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OnboardingStatusReader {

    private final JdbcTemplate jdbc;

    public boolean isCompleted(Long userId) {
        return status(userId).completed();
    }

    /** 초기 설정의 두 조건을 따로. 내 정보 조회(GET /api/users/me)의 onboarding에 쓴다. */
    public Status status(Long userId) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_card WHERE user_id = ? AND status IN ('ACTIVE', 'INACTIVE')) AS card_registered,
                       EXISTS (SELECT 1 FROM linked_account WHERE user_id = ? AND status = 'CONNECTED' AND is_primary = TRUE)
                           AS primary_account_set
                """, (rs, rowNum) -> new Status(rs.getBoolean("card_registered"), rs.getBoolean("primary_account_set")),
                userId, userId);
    }

    /**
     * @param cardRegistered    등록된 카드(ACTIVE·INACTIVE)가 1장 이상
     * @param primaryAccountSet 연결된 기준 계좌가 있음
     */
    public record Status(boolean cardRegistered, boolean primaryAccountSet) {

        /** 둘 다면 초기 설정 완료. */
        public boolean completed() {
            return cardRegistered && primaryAccountSet;
        }
    }
}
