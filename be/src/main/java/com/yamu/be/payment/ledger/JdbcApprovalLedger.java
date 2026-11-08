package com.yamu.be.payment.ledger;

import com.yamu.be.payment.approve.ApprovalLedger;
import com.yamu.be.payment.approve.ApprovalOutcome;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.recommend.RecommendationResult.BenefitLine;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.ExpectedLimitUse;
import com.yamu.be.payment.recommend.RecommendationResult.UnallocatedBenefitGroup;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * 승인 원장 반영(Task 5.2.5, 명세 「결제 승인 요청」 처리 순서 5, 「결제 상태·예외 처리」 6장).
 * 결제 행을 APPROVED로 저장한 tx2 안에서 불린다. 여기서 예외가 나면 승인까지 롤백되어 PROCESSING에 남는다.
 *
 * <pre>
 * payment_benefit           받은 혜택 한 줄씩(금액이 확정되고 0원보다 큰 줄). 원화 환산액은 KRW면 금액, POINT면 금액 × 환산율이
 *                           정수일 때만(소수·환산율 없음은 NULL = 미환산, 2026-11-05 결정)
 * payment_limit_usage       한도 그룹별 사용 직전 남은 한도·사용량(승인 때 다시 계산한 후보의 expectedLimitUses). 사용량 0은 적지 않는다
 * monthly_limit_usage       MONTHLY 한도만, 원래 사용 월(POS 요청 시각의 한국 월)에 원자적으로 더한다. 행이 없으면 만든다(임시 A5)
 * card_monthly_performance  실적 인정액(BE3 계산, Task 5.2.11)이 0원보다 클 때만 실적 월에 원자적으로 더한다.
 *                           제외 확정(0)·미확인(NULL)은 더하지 않는다
 * </pre>
 *
 * <ul>
 *   <li>사용일·사용 월은 POS 요청 시각의 한국 날짜다. 추천 엔진이 같은 날짜로 사용량을 읽고 남은 한도를 계산했기 때문이다.</li>
 *   <li>한도 사용량·남은 한도는 정수다(반올림 단위가 SMALLINT 1 이상이라 엔진 값이 정수 배수). 정수가 아니면 그 줄을 적지 않고 오류 로그를 남긴다.</li>
 *   <li>사용량이 사용 직전 남은 한도보다 크면(엔진 결과 불일치) 남은 한도만큼만 적고 경고한다(DB CHECK). 돈은 이미 움직였으므로 승인을 막지 않는다.</li>
 *   <li>상품이 다른 혜택·한도 그룹, 공유 한도 배분이 미확정인 묶음(사용량 없음)은 적지 않고 로그로 남긴다(임시 A4).</li>
 *   <li>같은 결제를 두 번 적으면 UNIQUE(결제·혜택, 결제·한도 그룹)가 막아 tx2가 롤백된다. 결제 상태 검사가 먼저 막으므로 정상 흐름에서는 일어나지 않는다.</li>
 * </ul>
 *
 * <p>추천 엔진(BE3)의 계산을 다시 하지 않는다. 엔진이 낸 값을 그대로 적고, 원화 환산만 같은 식(금액 × 환산율)으로 한다(임시: BE3 결과에 원화 환산액이 생기면 그 값을 쓴다).
 */
@Slf4j
@Component
public class JdbcApprovalLedger implements ApprovalLedger {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcApprovalLedger(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Recorded recordApproval(PaymentTransaction payment, ApprovalOutcome.Approved outcome) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Candidate basis = basisOf(payment, outcome.basis());
        Map<Long, Long> benefitIds = basis == null ? Map.of() : writeBenefits(payment, outcome.benefits(), now);
        int limitRows = basis == null ? 0 : writeLimitUses(payment, basis, now);
        addPerformance(payment, outcome.recognizedPerformanceAmount(), outcome.performanceMonth(), now);
        log.info("승인 원장 반영 paymentId={} benefits={} limitUsages={} recognizedPerformance={}", payment.getId(),
                benefitIds.size(), limitRows, outcome.recognizedPerformanceAmount());
        return new Recorded(benefitIds);
    }

    /** 후보가 이 결제의 카드 것일 때만 근거로 쓴다. 다르면 혜택·한도를 적지 않는다. */
    private static Candidate basisOf(PaymentTransaction payment, Candidate basis) {
        if (basis == null) {
            return null;
        }
        if (!basis.card().userCardId().equals(payment.getUserCardId())
                || !basis.card().cardProductId().equals(payment.getCardProductId())) {
            log.error("원장 근거 후보의 카드가 결제 카드와 다름, 혜택·한도를 적지 않음 paymentId={} paymentCard={} basisCard={}",
                    payment.getId(), payment.getUserCardId(), basis.card().userCardId());
            return null;
        }
        return basis;
    }

    private Map<Long, Long> writeBenefits(PaymentTransaction payment, List<BenefitLine> lines, LocalDateTime now) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        Map<Long, BenefitUnit> units = units(payment.getCardProductId(),
                lines.stream().map(BenefitLine::benefitId).toList());
        Map<Long, Long> ids = new LinkedHashMap<>();
        for (BenefitLine line : lines) {
            BenefitUnit unit = units.get(line.benefitId());
            if (unit == null) {
                log.error("결제 상품의 혜택이 아니어서 payment_benefit에 적지 않음 paymentId={} benefitId={} cardProductId={}",
                        payment.getId(), line.benefitId(), payment.getCardProductId());
                continue;
            }
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update("""
                    INSERT INTO payment_benefit (payment_transaction_id, card_product_id, card_benefit_id, benefit_value,
                                                 value_unit, krw_amount, created_at)
                    VALUES (:payment, :product, :benefit, :value, :unit, :krw, :now)
                    """, new MapSqlParameterSource()
                    .addValue("payment", payment.getId())
                    .addValue("product", payment.getCardProductId())
                    .addValue("benefit", line.benefitId())
                    .addValue("value", BigDecimal.valueOf(line.amount()))
                    .addValue("unit", unit.unit())
                    .addValue("krw", unit.krwAmount(line.amount()))
                    .addValue("now", now), key, new String[] {"payment_benefit_id"});
            ids.put(line.benefitId(), key.getKey().longValue());
        }
        return ids;
    }

    private Map<Long, BenefitUnit> units(Long cardProductId, List<Long> benefitIds) {
        return jdbc.query("""
                SELECT card_benefit_id, value_unit, point_krw_rate FROM card_benefit
                WHERE card_product_id = :product AND card_benefit_id IN (:ids)
                """, new MapSqlParameterSource().addValue("product", cardProductId).addValue("ids", benefitIds),
                (rs, i) -> new BenefitUnit(rs.getLong("card_benefit_id"), rs.getString("value_unit"),
                        rs.getBigDecimal("point_krw_rate")))
                .stream().collect(Collectors.toMap(BenefitUnit::benefitId, u -> u));
    }

    /** @return 적은 payment_limit_usage 행 수 */
    private int writeLimitUses(PaymentTransaction payment, Candidate basis, LocalDateTime now) {
        List<ExpectedLimitUse> uses = basis.expectedLimitUses();
        Set<Long> covered = new HashSet<>();
        int written = 0;
        if (!uses.isEmpty()) {
            Map<Long, String> periods = periods(payment.getCardProductId(),
                    uses.stream().map(ExpectedLimitUse::limitGroupId).toList());
            LocalDate usageDate = LedgerDates.koreanDate(payment.getRequestedAt());
            LocalDate usageMonth = usageDate.withDayOfMonth(1);
            for (ExpectedLimitUse use : uses) {
                covered.add(use.limitGroupId());
                if (writeLimitUse(payment, use, periods.get(use.limitGroupId()), usageDate, usageMonth, now)) {
                    written++;
                }
            }
        }
        for (UnallocatedBenefitGroup group : basis.unallocatedBenefitGroups()) {
            if (!covered.contains(group.limitGroupId())) {
                log.warn("공유 한도 배분 미확정 묶음의 한도 사용량이 결과에 없어 차감하지 않음(임시 A4) paymentId={} limitGroupId={} "
                        + "total={} unit={}", payment.getId(), group.limitGroupId(), group.totalAmount(), group.unit());
            }
        }
        return written;
    }

    private boolean writeLimitUse(PaymentTransaction payment, ExpectedLimitUse use, String period,
            LocalDate usageDate, LocalDate usageMonth, LocalDateTime now) {
        Long consumed = exact(use.consumed());
        Long remaining = exact(use.remainingBefore());
        if (consumed == null || remaining == null) {
            log.error("한도 사용량·남은 한도가 정수가 아니어서 적지 않음, 운영 확인 paymentId={} limitGroupId={} consumed={} "
                    + "remainingBefore={}", payment.getId(), use.limitGroupId(), use.consumed(), use.remainingBefore());
            return false;
        }
        if (consumed <= 0) {
            return false;
        }
        if (period == null) {
            log.error("결제 상품의 한도 그룹이 아니어서 적지 않음 paymentId={} limitGroupId={} cardProductId={}",
                    payment.getId(), use.limitGroupId(), payment.getCardProductId());
            return false;
        }
        if (remaining < 0) {
            remaining = 0L;
        }
        if (consumed > remaining) {
            log.warn("한도 사용량이 사용 직전 남은 한도보다 커서 남은 한도만큼만 적음 paymentId={} limitGroupId={} consumed={} "
                    + "remainingBefore={}", payment.getId(), use.limitGroupId(), consumed, remaining);
            consumed = remaining;
            if (consumed == 0) {
                return false;
            }
        }
        jdbc.update("""
                INSERT INTO payment_limit_usage (payment_transaction_id, card_product_id, limit_group_id, remaining_before,
                                                 consumed_value, usage_date, usage_month, restored_at,
                                                 created_at, updated_at, version)
                VALUES (:payment, :product, :group, :remaining, :consumed, :date, :month, NULL, :now, :now, 0)
                """, new MapSqlParameterSource()
                .addValue("payment", payment.getId())
                .addValue("product", payment.getCardProductId())
                .addValue("group", use.limitGroupId())
                .addValue("remaining", remaining)
                .addValue("consumed", consumed)
                .addValue("date", usageDate)
                .addValue("month", usageMonth)
                .addValue("now", now));
        if (LedgerDates.MONTHLY.equals(period)) {
            // 원자적으로 더한다. 행이 없으면 이 사용량으로 만든다(임시 A5)
            jdbc.update("""
                    INSERT INTO monthly_limit_usage (user_card_id, card_product_id, limit_group_id, usage_month, used_value,
                                                     created_at, updated_at, version)
                    VALUES (:card, :product, :group, :month, :consumed, :now, :now, 0) AS added
                    ON DUPLICATE KEY UPDATE used_value = monthly_limit_usage.used_value + added.used_value,
                                            updated_at = added.updated_at,
                                            version = monthly_limit_usage.version + 1
                    """, new MapSqlParameterSource()
                    .addValue("card", payment.getUserCardId())
                    .addValue("product", payment.getCardProductId())
                    .addValue("group", use.limitGroupId())
                    .addValue("month", usageMonth)
                    .addValue("consumed", consumed)
                    .addValue("now", now));
        }
        return true;
    }

    private Map<Long, String> periods(Long cardProductId, List<Long> limitGroupIds) {
        return jdbc.query("""
                SELECT limit_group_id, period_type FROM limit_group
                WHERE card_product_id = :product AND limit_group_id IN (:ids)
                """, new MapSqlParameterSource().addValue("product", cardProductId).addValue("ids", limitGroupIds),
                (rs, i) -> Map.entry(rs.getLong("limit_group_id"), rs.getString("period_type")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void addPerformance(PaymentTransaction payment, Long amount, LocalDate performanceMonth,
            LocalDateTime now) {
        if (amount == null || amount <= 0) { // 제외(0)·미확인(null)은 월 합계에 더하지 않는다
            return;
        }
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount,
                                                      created_at, updated_at, version)
                VALUES (:card, :month, :amount, :now, :now, 0) AS added
                ON DUPLICATE KEY UPDATE recognized_amount = card_monthly_performance.recognized_amount + added.recognized_amount,
                                        updated_at = added.updated_at,
                                        version = card_monthly_performance.version + 1
                """, new MapSqlParameterSource()
                .addValue("card", payment.getUserCardId())
                .addValue("month", performanceMonth.withDayOfMonth(1))
                .addValue("amount", amount)
                .addValue("now", now));
    }

    /** 정수면 그 값, 소수가 있으면 null. */
    private static Long exact(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** 혜택의 단위와 포인트 환산율(card_benefit). */
    private record BenefitUnit(long benefitId, String unit, BigDecimal pointKrwRate) {

        /** 원화 환산액. KRW는 금액 그대로, POINT는 금액 × 환산율이 정수일 때만. 환산율이 없거나 소수가 나오면 NULL(미환산). */
        Long krwAmount(long value) {
            if ("KRW".equals(unit)) {
                return value;
            }
            if (pointKrwRate == null) {
                return null;
            }
            return exact(pointKrwRate.multiply(BigDecimal.valueOf(value)));
        }
    }
}
