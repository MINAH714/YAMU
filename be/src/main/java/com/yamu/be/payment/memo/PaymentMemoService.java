package com.yamu.be.payment.memo;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 결제 메모 저장·삭제(Task 5.3.5, API 명세 「결제 메모」, FR-050). 메모는 사용자에게만 보이고 소비분석·실적·추천에 쓰지 않는다.
 *
 * <p>저장은 {@code memo}·{@code memo_updated_at} 두 칸만 고치는 SQL 한 번이다(2026-11-06 결정 M2).
 * <ul>
 *   <li>{@code version}을 올리지 않는다. 엔티티로 저장하면 같은 행을 쓰는 승인 결과 저장·취소·결과 불명 복구가 낙관적 락 충돌로 실패할 수 있다.
 *       반대로 그쪽 저장이 메모를 옛 값으로 덮지 않게 엔티티는 메모 칸을 쓰지 않는다({@code updatable=false}, {@code @DynamicUpdate}).</li>
 *   <li>{@code updated_at}도 바꾸지 않는다. 결과 불명 복구가 PROCESSING 결제의 유예를 {@code updated_at}으로 재므로, 메모가 유예를 늘리면 안 된다.</li>
 *   <li>결제 상태는 보지 않는다. 취소·거절·처리 중인 결제에도 남길 수 있다(결정 M5).</li>
 *   <li>같은 결제에 두 요청이 겹치면 행 잠금으로 차례로 적고 나중 값이 남는다.</li>
 *   <li>없는 결제와 다른 사용자의 결제는 구분하지 않고 404다. 로그에는 메모 내용을 남기지 않는다(개인정보를 적을 수 있다).</li>
 * </ul>
 */
@Slf4j
@Service
public class PaymentMemoService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PaymentMemoService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public PaymentMemoResponse save(AuthUser me, Long paymentId, PaymentMemoRequest request) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        int updated = jdbc.update("""
                UPDATE payment_transaction SET memo = :memo, memo_updated_at = :now
                WHERE payment_transaction_id = :paymentId AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("memo", request.memo())
                .addValue("now", now)
                .addValue("paymentId", paymentId)
                .addValue("userId", me.userId()));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        log.info("결제 메모 {} paymentId={}", request.memo() == null ? "삭제" : "저장", paymentId);
        return new PaymentMemoResponse(paymentId, request.memo(), now);
    }
}
