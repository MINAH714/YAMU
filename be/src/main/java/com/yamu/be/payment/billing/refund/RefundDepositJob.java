package com.yamu.be.payment.billing.refund;

import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import com.yamu.be.payment.billing.RefundStatus;
import com.yamu.be.payment.billing.refund.RefundDepositor.Outcome;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 신용카드 취소 환급 입금 배치(Task 5.4.4). 기한이 정해진 대기 환급을 번호 순으로 50건씩 훑어 {@link RefundDepositor}로 한 건씩 입금한다.
 * 기한을 기다리지 않고 정해지는 대로 보낸다(기한 안에 들어갈 가능성을 높인다). 한 건이 실패해도 나머지는 계속한다.
 *
 * <p>{@code yamu.payment.refund.deposit-enabled=true}일 때만 만든다(기본 끔). 매일 {@code deposit-cron}(한국 시간)에 돈다.
 * 임시: 운영 정기 작업 수동 실행 API(S15P31E103-206)가 develop에 들어오면 그 작업으로 등록해 같은 잠금·켜기 설정을 쓴다.
 * 그 전에는 서버가 여러 대여도 환급 행 잠금과 남긴 요청 번호가 같은 환급을 두 번 보내지 않게 막는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yamu.payment.refund", name = "deposit-enabled", havingValue = "true")
public class RefundDepositJob {

    static final int PAGE_SIZE = 50;
    static final int MAX_PER_RUN = 500;

    private final RefundRepository refunds;
    private final RefundDepositor depositor;

    public RefundDepositJob(RefundRepository refunds, RefundDepositor depositor) {
        this.refunds = refunds;
        this.depositor = depositor;
    }

    @Scheduled(cron = "${yamu.payment.refund.deposit-cron}", zone = "Asia/Seoul")
    public void scheduled() {
        run();
    }

    public Report run() {
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int errors = 0;
        int seen = 0;
        long lastId = 0;
        while (seen < MAX_PER_RUN) {
            List<Refund> page = refunds.findTop50ByStatusAndDueDateIsNotNullAndIdGreaterThanOrderByIdAsc(
                    RefundStatus.PENDING, lastId);
            for (Refund refund : page) {
                lastId = refund.getId();
                seen++;
                try {
                    outcomes.merge(depositor.deposit(refund.getId()), 1, Integer::sum);
                } catch (RuntimeException e) {
                    errors++;
                    log.warn("환급 입금 실패, 다음 실행에 다시 refundId={}", refund.getId(), e);
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        if (seen >= MAX_PER_RUN) {
            log.warn("입금할 환급이 한 번에 볼 수 있는 수({})를 넘음, 다음 실행에 이어서", MAX_PER_RUN);
        }
        Report report = new Report(outcomes, errors);
        log.info("환급 입금 배치 끝 seen={} outcomes={} errors={}", seen, outcomes, errors);
        return report;
    }

    /**
     * @param outcomes 결과별 건수
     * @param errors   예외로 끝난 건수(금융망 조회·채번 실패 등). 다음 실행에 다시 본다
     */
    public record Report(Map<Outcome, Integer> outcomes, int errors) {

        public Report {
            outcomes = Map.copyOf(outcomes);
        }

        public int count(Outcome outcome) {
            return outcomes.getOrDefault(outcome, 0);
        }
    }
}
