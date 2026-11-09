package com.yamu.be.performance;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * performance_decision. 실적 유불리 분석을 보고 사용자가 고른 이번 달 판단(S15P31E103-375). 카드·월당 한 줄이다.
 *
 * <p>다시 보내면 덮어쓰고, 되돌리면(decision null) 줄은 남기고 판단만 비운다. 판단이 있는 동안 그달 그 카드의 실적 부족 알림을
 * 멈출 수 있다({@link #isNotificationsMuted()}). 되돌리면 알림 멈춤도 풀린다.
 */
@Entity
@Table(name = "performance_decision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerformanceDecision extends BaseTimeEntity {

    /** 사용자가 고를 수 있는 판단. 이름은 앱 {@code PerfDecision}·API 명세서와 같다. */
    public enum Choice {
        /** 채운다(예정 지출 옮기기 등) */
        FILL,
        /** 평소대로 쓰며 지켜본다 */
        KEEP,
        /** 채우지 않는다 */
        SKIP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "performance_decision_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_card_id", nullable = false)
    private Long userCardId;

    /** 판단한 실적 월(한국 기준)의 1일. */
    @Column(name = "performance_month", nullable = false)
    private LocalDate performanceMonth;

    /** null이면 되돌린 상태. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Choice decision;

    /** 기록할 때 서버가 다시 계산한 판정. 판정할 수 없었으면 null. */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PerformanceGuide guide;

    /** 앱이 근거로 본 분석의 asOf. */
    @Column(name = "analysis_version", length = 40)
    private String analysisVersion;

    @Column(name = "notifications_muted", nullable = false)
    private boolean notificationsMuted;

    /** UTC 마지막으로 판단하거나 되돌린 시각. */
    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    @Version
    private Long version;

    PerformanceDecision(Long userId, Long userCardId, LocalDate performanceMonth) {
        this.userId = userId;
        this.userCardId = userCardId;
        this.performanceMonth = performanceMonth;
    }

    /**
     * 판단을 덮어쓴다. decision이 null이면 되돌리기라 알림 멈춤도 푼다.
     *
     * @return 저장된 값이 바뀌었는지. 같은 값을 다시 보내면(연타) false라 decidedAt도 그대로 둔다
     */
    boolean record(Choice decision, PerformanceGuide guide, String analysisVersion, boolean muteNotifications,
            LocalDateTime now) {
        boolean muted = decision != null && muteNotifications;
        if (decidedAt != null && this.decision == decision && this.notificationsMuted == muted
                && java.util.Objects.equals(this.analysisVersion, analysisVersion)) {
            return false;
        }
        this.decision = decision;
        this.guide = guide;
        this.analysisVersion = analysisVersion;
        this.notificationsMuted = muted;
        this.decidedAt = now;
        return true;
    }
}
