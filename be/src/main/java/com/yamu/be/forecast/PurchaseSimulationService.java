package com.yamu.be.forecast;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 가상 구매 시뮬레이션(명세 「가상 구매 시뮬레이션」 POST /api/forecasts/simulations, Task 7.2.2).
 * 요청을 검증하고, 카드·혜택을 정한 뒤, 잔고예보와 같은 재료에 가상 구매 출금을 얹어 전후를 비교한다. 아무것도 저장하지 않는다.
 *
 * <p>추천·혜택은 {@link SimulationRecommender}가 맡는다. 연결 전에는 계산할 수 없어 카드를 고르지 않은 요청은 422이고,
 * 고른 요청은 혜택·대안·실적 영향 없이 잔고만 비교한다(2026-11-05 결정). 추천은 체크카드 잔액을 조회할 수 있어
 * 트랜잭션 없이 부른다. 생활비 원천이 소비 분류를 쓰므로 이 서비스도 읽기 전용 트랜잭션 안에서 부르지 않는다.
 */
@Service
public class PurchaseSimulationService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final String CREDIT_LIMIT_SKIPPED = "SKIPPED";

    private final ForecastService forecasts;
    private final CardQueryService cards;
    private final CardNameReader cardNames;
    private final SimulationRecommender recommender;
    private final BillingSchedule billingSchedule;
    private final Clock clock;

    PurchaseSimulationService(ForecastService forecasts, CardQueryService cards, CardNameReader cardNames,
            SimulationRecommender recommender, BusinessDayCalendar calendar, Clock clock) {
        this.forecasts = forecasts;
        this.cards = cards;
        this.cardNames = cardNames;
        this.recommender = recommender;
        this.billingSchedule = new BillingSchedule(calendar);
        this.clock = clock;
    }

    /**
     * 가상 구매 전후 비교를 계산한다.
     *
     * @param authenticatedUserId 로그인 사용자
     * @param request             요청 본문
     * @return 카드·혜택·출금 일정·전후 비교
     */
    public SimulationResponse simulate(Long authenticatedUserId, SimulationRequest request) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 계좌·금융망을 읽기 전에 요청만으로 알 수 있는 오류부터 거절한다.
        PaymentTerms terms = request.toTerms();
        validateDescription(request);
        Integer days = request.days();
        LocalDate today = LocalDate.now(clock.withZone(KOREA));
        LocalDate purchaseDate = request.purchaseDate() == null ? today : request.purchaseDate();
        if (purchaseDate.isBefore(today)) {
            throw invalid("purchaseDate", "구매일은 오늘 이후로 골라 주세요.");
        }

        // 고른 카드는 추천을 부르기 전에 확인한다. 남의·없는 카드 404, 결제할 수 없는 카드 400.
        UserCardRef chosen = null;
        if (request.userCardId() != null) {
            chosen = cards.requireRegisteredCard(authenticatedUserId, request.userCardId());
            usableCard(chosen, terms);
        }
        Optional<SimulationRecommender.Recommendation> recommendation = recommender.recommend(
                new SimulationRecommender.Request(authenticatedUserId, request.userCardId(), terms,
                        request.productCategoryCode(), request.merchantCategoryCode(), request.merchantId(),
                        purchaseDate));
        if (request.userCardId() == null && recommendation.isEmpty()) {
            // 추천 엔진 연결 전에는 추천 카드를 정할 수 없다. 아무 카드나 추천처럼 보이지 않게 거절한다(2026-11-05 결정).
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "추천 카드를 아직 계산할 수 없어요. 카드를 골라 주세요.",
                    Map.of("reason", "RECOMMENDATION_UNAVAILABLE"));
        }
        long cardId = request.userCardId() != null ? request.userCardId() : recommendation.get().userCardId();
        if (recommendation.isPresent() && recommendation.get().userCardId() != cardId) {
            throw new IllegalStateException("추천 결과의 카드가 지정 카드와 다르다: " + recommendation.get().userCardId());
        }
        UserCardRef card = chosen;
        if (card == null) {
            // 추천 결과도 믿지 않는다. 내 카드가 아니거나 쓸 수 없는 카드면 엔진 계약 위반이다.
            card = cards.findRegisteredCard(authenticatedUserId, cardId)
                    .orElseThrow(() -> new IllegalStateException("추천 카드가 사용자의 카드가 아니다: " + cardId));
            usableCard(card, terms);
        }
        SimulationRecommender.Benefit benefit = recommendation.map(SimulationRecommender.Recommendation::benefit)
                .orElse(null);
        long charged = benefit == null ? terms.amount() : benefit.chargedAmount();
        if (charged < 1 || charged > terms.amount()) {
            throw new IllegalStateException("혜택 계산의 청구 금액이 결제 금액 범위를 벗어났다: " + charged);
        }
        List<BillingSchedule.Withdrawal> withdrawals = withdrawals(card, purchaseDate, charged, terms);

        ForecastService.Prepared prepared = forecasts.prepare(authenticatedUserId, request.accountIds(),
                days == null ? null : BalanceForecast.Until.DAYS, days, false);
        PurchaseSimulation simulation = PurchaseSimulator.simulate(new PurchaseSimulator.Input(
                prepared.startBalances(), prepared.events(), prepared.horizon().from(), prepared.horizon().to(),
                prepared.dailyLiving(), prepared.alertThreshold(), purchaseDate, card.linkedAccountId(), withdrawals));

        return new SimulationResponse(
                new SimulationResponse.Input(terms.amount(), request.productCategoryCode(), request.productName(),
                        request.merchantCategoryCode(), request.merchantId(), purchaseDate, terms.plan(),
                        terms.installmentMonths()),
                new SimulationResponse.Card(cardId, cardNames.name(authenticatedUserId, cardId), card.cardType(),
                        request.userCardId() == null),
                benefit,
                simulation.schedule().stream()
                        .map(w -> new SimulationResponse.Schedule(w.date(), w.amount(), w.label())).toList(),
                simulation.comparison(), simulation.series(),
                recommendation.map(SimulationRecommender.Recommendation::performanceImpact).orElse(null),
                recommendation.map(SimulationRecommender.Recommendation::alternatives).orElse(null),
                card.cardType() == CardType.CREDIT ? new SimulationResponse.CreditLimit(CREDIT_LIMIT_SKIPPED, null) : null,
                simulation.outsideSelectionImpact(),
                request.includeAccountDetails() ? simulation.accountComparisons() : null,
                message(simulation),
                true);
    }

    /** 물품·업종·가맹점 중 하나는 있어야 하고(명세), 상품명·코드는 길이를 넘지 않아야 한다. */
    private static void validateDescription(SimulationRequest request) {
        if (request.productCategoryCode() == null && request.merchantCategoryCode() == null
                && request.merchantId() == null) {
            throw new BusinessException(ErrorCode.MERCHANT_OR_CATEGORY_REQUIRED);
        }
        if (request.merchantId() != null && request.merchantId() <= 0) {
            throw invalid("merchantId", "올바른 가맹점을 골라 주세요.");
        }
        if (request.productName() != null && request.productName().length() > SimulationRequest.MAX_PRODUCT_NAME) {
            throw invalid("productName", "상품명은 " + SimulationRequest.MAX_PRODUCT_NAME + "자까지입니다.");
        }
        tooLong("productCategoryCode", request.productCategoryCode());
        tooLong("merchantCategoryCode", request.merchantCategoryCode());
    }

    private static void tooLong(String field, String code) {
        if (code != null && code.length() > SimulationRequest.MAX_CODE) {
            throw invalid(field, "코드는 " + SimulationRequest.MAX_CODE + "자까지입니다.");
        }
    }

    /** 결제할 수 있는 카드인지. 사용자가 꺼 둔 카드·카드사 정지·만료 카드는 400, 체크카드 할부는 INVALID_INSTALLMENT다. */
    private static void usableCard(UserCardRef card, PaymentTerms terms) {
        if (card.registrationStatus() != CardRegistrationStatus.ACTIVE
                || card.issuerStatus() != CardIssuerStatus.NORMAL) {
            throw invalid("userCardId", "지금 결제할 수 없는 카드예요.");
        }
        if (!terms.allows(card.cardType())) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "plan", "체크카드는 일시불만 가능합니다.");
        }
    }

    /** 카드 출금 회차. 출금 계좌·결제일을 몰라 일정을 만들 수 없으면 0원·임의 날짜로 채우지 않고 422다. */
    private List<BillingSchedule.Withdrawal> withdrawals(UserCardRef card, LocalDate purchaseDate, long charged,
            PaymentTerms terms) {
        if (card.linkedAccountId() == null) {
            throw insufficient("CARD_ACCOUNT_UNKNOWN", "카드의 출금 계좌를 알 수 없어요.");
        }
        if (card.cardType() == CardType.CREDIT && (card.paymentDay() == null
                || !BillingCycle.supports(card.paymentDay(), card.billingCutoffDays()))) {
            throw insufficient("BILLING_CYCLE_UNKNOWN", "카드 결제일을 알 수 없어 출금일을 계산할 수 없어요.");
        }
        return billingSchedule.schedule(card.cardType(), card.paymentDay(), card.billingCutoffDays(), purchaseDate,
                charged, terms.installmentMonthsOrOne());
    }

    /**
     * 구매 뒤 가장 낮은 잔액과 기간 밖 금액을 알리는 문장. 구매를 권하거나 말리는 문구는 넣지 않는다(FR-032).
     * 예: "11월 16일 잔액이 12,000원까지 내려가요. 예보 기간 밖에서 800,000원이 더 빠져요."
     */
    static String message(PurchaseSimulation simulation) {
        if (simulation.outsideSelectionImpact() != null) {
            return "선택하지 않은 계좌에서 " + won(-simulation.outsideSelectionImpact().amount())
                    + "이 빠져 비교 결과는 같아요.";
        }
        PurchaseSimulation.Snapshot after = simulation.comparison().after();
        String date = after.minBalanceDate().getMonthValue() + "월 " + after.minBalanceDate().getDayOfMonth() + "일";
        StringBuilder text = new StringBuilder(after.minBalance() < 0
                ? date + " 잔액이 " + won(-after.minBalance()) + " 모자라요."
                : date + " 잔액이 " + won(after.minBalance()) + "까지 내려가요.");
        if (simulation.comparison().beyondPeriodImpact() < 0) {
            text.append(" 예보 기간 밖에서 ").append(won(-simulation.comparison().beyondPeriodImpact()))
                    .append("이 더 빠져요.");
        }
        return text.toString();
    }

    private static String won(long amount) {
        return String.format("%,d원", amount);
    }

    private static BusinessException insufficient(String reason, String message) {
        return new BusinessException(ErrorCode.DATA_INSUFFICIENT, message, Map.of("reason", reason));
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }
}
