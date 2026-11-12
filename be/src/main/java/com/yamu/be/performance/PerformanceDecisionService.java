package com.yamu.be.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실적 판단 기록(S15P31E103-375, 2026-11-10 결정). 카드·월당 한 줄을 덮어쓴다.
 *
 * <ul>
 *   <li>decision null은 되돌리기다. 줄은 남기고 판단과 알림 멈춤을 푼다.</li>
 *   <li>같은 값을 다시 보내면(버튼 연타·재시도) 아무것도 바꾸지 않고 같은 결과를 돌려준다.</li>
 *   <li>같은 카드의 기록은 카드 행을 잠가 한 줄로 세운다. 첫 기록이 동시에 와도 줄은 하나다.</li>
 *   <li>analysisVersion은 저장만 한다. 같은 달의 옛 분석이어도 받는다(앱이 예정 지출을 옮긴 뒤 옛 버전으로 FILL을 보내기 때문).
 *       지난 달 분석이면 409 MONTH_CLOSED, 형식이 틀렸거나 미래 시각이면 400.</li>
 *   <li>yearMonth는 생략하면 이번 달. 지난 달이면 409 MONTH_CLOSED, 다음 달 이후면 400.</li>
 *   <li>guide는 기록할 때 서버가 다시 계산한 판정이다. 데이터가 모자라 판정할 수 없으면 null로 두고 기록은 받는다.</li>
 * </ul>
 */
@Service
public class PerformanceDecisionService {

    /** 기기 시계가 조금 빠른 앱이 보낸 분석 시각을 미래로 거절하지 않게 둔 여유. */
    static final long VERSION_CLOCK_SKEW_MINUTES = 5;

    private final PerformanceAnalysisService analysis;
    private final PerformanceDecisionRepository decisions;
    private final Clock clock;

    PerformanceDecisionService(PerformanceAnalysisService analysis, PerformanceDecisionRepository decisions,
            Clock clock) {
        this.analysis = analysis;
        this.decisions = decisions;
        this.clock = clock;
    }

    record Command(PerformanceDecision.Choice decision, String analysisVersion, boolean muteNotifications,
            YearMonth yearMonth) {
    }

    /**
     * 카드 행 잠금을 기다린 뒤 앞 요청이 커밋한 줄을 읽도록 READ COMMITTED로 연다(REPEATABLE READ면 잠금 전에 잡힌 스냅샷을 읽어
     * 같은 줄을 또 넣으려다 UNIQUE에 걸린다).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PerformanceDecisionResponse decide(long userId, long userCardId, Command command) {
        UserCard card = analysis.registeredCard(userId, userCardId);
        if (decisions.lockOwnedCard(userId, userCardId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        LocalDate today = analysis.today();
        YearMonth month = YearMonth.from(today);
        requireThisMonth(command.yearMonth(), month);
        requireVersion(command.analysisVersion(), month);

        PerformanceGuide guide = command.decision() == null ? null : analysis.currentGuide(userId, card);
        PerformanceDecision row = decisions
                .findByUserIdAndUserCardIdAndPerformanceMonth(userId, userCardId, month.atDay(1))
                .orElseGet(() -> new PerformanceDecision(userId, userCardId, month.atDay(1)));
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        if (row.record(command.decision(), guide, command.analysisVersion(), command.muteNotifications(), now)) {
            decisions.saveAndFlush(row);
        }
        return new PerformanceDecisionResponse(userCardId, month.toString(), row.getDecision(), row.getGuide(),
                row.getDecidedAt(), row.getAnalysisVersion(),
                new PerformanceDecisionResponse.Effects(row.isNotificationsMuted(),
                        row.getDecision() == PerformanceDecision.Choice.SKIP),
                row.getDecision() != null);
    }

    /**
     * 요청 본문을 명령으로 바꾼다. decision 칸은 꼭 있어야 한다(null이면 되돌리기, 빠지면 400).
     * 값은 FILL·KEEP·SKIP 문자열만 받는다(숫자를 순번으로 읽지 않는다).
     */
    static Command command(PerformanceDecisionRequest request) {
        if (request == null || request.decision() == null || request.decision().isMissingNode()) {
            throw invalid("decision", "decision을 보내 주세요. 되돌리려면 null을 보냅니다.");
        }
        PerformanceDecision.Choice decision = null;
        JsonNode node = request.decision();
        if (!node.isNull()) {
            if (!node.isTextual()) {
                throw invalid("decision", "decision은 FILL, KEEP, SKIP 중 하나입니다.");
            }
            try {
                decision = PerformanceDecision.Choice.valueOf(node.textValue());
            } catch (IllegalArgumentException e) {
                throw invalid("decision", "decision은 FILL, KEEP, SKIP 중 하나입니다.");
            }
        }
        YearMonth yearMonth = null;
        if (request.yearMonth() != null) {
            try {
                yearMonth = YearMonth.parse(request.yearMonth());
            } catch (DateTimeParseException e) {
                throw invalid("yearMonth", "yearMonth는 2026-11처럼 보내 주세요.");
            }
        }
        String version = request.analysisVersion() == null || request.analysisVersion().isBlank() ? null
                : request.analysisVersion().strip();
        return new Command(decision, version,
                request.muteNotifications() == null || request.muteNotifications(), yearMonth);
    }

    private static void requireThisMonth(YearMonth requested, YearMonth month) {
        if (requested == null || requested.equals(month)) {
            return;
        }
        if (requested.isBefore(month)) {
            throw new BusinessException(ErrorCode.MONTH_CLOSED);
        }
        throw invalid("yearMonth", "이번 달 판단만 기록할 수 있습니다.");
    }

    /** 분석 시각(UTC, "2026-11-10T05:30:00Z" 또는 Z 없는 같은 모양)의 한국 월이 이번 달인지. */
    private void requireVersion(String version, YearMonth month) {
        if (version == null) {
            return;
        }
        Instant at;
        try {
            at = version.endsWith("Z") ? Instant.parse(version)
                    : LocalDateTime.parse(version).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw invalid("analysisVersion", "analysisVersion은 분석 응답의 asOf를 그대로 보내 주세요.");
        }
        if (at.isAfter(clock.instant().plus(VERSION_CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES))) {
            throw invalid("analysisVersion", "analysisVersion은 분석 응답의 asOf를 그대로 보내 주세요.");
        }
        if (YearMonth.from(at.atZone(PerformanceAnalysisService.KST)).isBefore(month)) {
            throw new BusinessException(ErrorCode.MONTH_CLOSED);
        }
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors",
                List.of(new ApiResponse.FieldErrorResponse(field, reason))));
    }
}
