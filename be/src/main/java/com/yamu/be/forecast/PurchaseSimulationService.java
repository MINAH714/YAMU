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
import com.yamu.be.merchant.MerchantCatalogService;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.approve.CreditLimitSource;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.CardBillRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 가상 구매 시뮬레이션(명세 「가상 구매 시뮬레이션」 POST /api/forecasts/simulations, Task 7.2.2).
 * 요청을 검증하고, 카드·혜택을 정한 뒤, 잔고예보와 같은 재료에 가상 구매 출금을 얹어 전후를 비교한다. 결제·청구·실적·혜택
 * 사용량·한도는 저장하지 않는다. 다만 잔고예보 조회처럼 생활비 원천이 미분류 결제의 자동 소비 분류를 채운다.
 *
 * <p>추천·혜택은 {@link SimulationRecommender}(추천 엔진)가 맡는다. 청구 금액은 결제 승인과 같이 결제 금액 − 즉시할인이고,
 * 0원이면 출금 회차가 없다. 추천은 체크카드 잔액을 조회할 수 있어 트랜잭션 없이 부른다. 생활비 원천이 소비 분류를 쓰므로 이 서비스도 읽기 전용 트랜잭션 안에서 부르지 않는다.
 */
@Service
public class PurchaseSimulationService {
    private final ForecastService forecasts;
    private final CardQueryService cards;
    private final CardNameReader cardNames;
    private final SimulationRecommender recommender;
    private final BillingSchedule billingSchedule;
    private final CreditLimitSource creditLimits;
    private final CardBillRepository bills;
    private final MerchantCatalogService catalog;

    PurchaseSimulationService(ForecastService forecasts, CardQueryService cards, CardNameReader cardNames,
            SimulationRecommender recommender, BusinessDayCalendar calendar, CreditLimitSource creditLimits,
            CardBillRepository bills, MerchantCatalogService catalog) {
        this.forecasts = forecasts;
        this.cards = cards;
        this.cardNames = cardNames;
        this.recommender = recommender;
        this.billingSchedule = new BillingSchedule(calendar);
        this.creditLimits = creditLimits;
        this.bills = bills;
        this.catalog = catalog;
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
        String paymentChannel = request.paymentChannel();
        Integer days = request.days();
        BalanceForecast.Until until = request.until();
        // 오늘은 요청마다 한 번만 정한다. 구매일 검사와 예보 시작일이 자정을 사이에 두고 어긋나면 계산기가 500을 낸다(코드 리뷰 반영).
        LocalDate today = forecasts.today();
        LocalDate purchaseDate = request.purchaseDate() == null ? today : request.purchaseDate();
        if (purchaseDate.isBefore(today)) {
            throw invalid("purchaseDate", "구매일은 오늘 이후로 골라 주세요.");
        }

        // 고른 카드는 추천을 부르기 전에 확인한다. 남의·없는 카드 404, 결제할 수 없는 카드 400 CARD_NOT_ELIGIBLE.
        UserCardRef chosen = null;
        if (request.userCardId() != null) {
            chosen = cards.requireRegisteredCard(authenticatedUserId, request.userCardId());
            usableCard(chosen, terms);
        }
        // 선택 계좌도 추천을 부르기 전에 확인한다. 추천 엔진은 체크카드 잔액을 금융망에서 읽으므로, 남의·연결 해제 계좌가 섞인
        // 요청은 금융망을 부르기 전에 404로 끝낸다(S15P31E103-333 Codex 검토). 잔액 서비스도 같은 확인을 다시 한다.
        forecasts.requireOwnedAccounts(authenticatedUserId, request.accountIds());
        // 업종·브랜드가 있을 때만 확인한다(브랜드면 DB의 업종을 쓴다). 물품만 있으면 혜택을 계산할 수 없다(2026-11-05 결정).
        MerchantCatalogService.Selection selection = request.merchantId() != null || request.merchantCategoryCode() != null
                ? catalog.resolveSelection(request.merchantId(), request.merchantCategoryCode()) : null;
        SimulationRecommender.Recommendation recommendation = recommend(authenticatedUserId, request, terms,
                paymentChannel, selection, purchaseDate, today);
        long cardId = recommendation.userCardId();
        if (request.userCardId() != null && cardId != request.userCardId()) {
            throw new IllegalStateException("추천 결과의 카드가 지정 카드와 다르다: " + cardId);
        }
        UserCardRef card = chosen;
        if (card == null) {
            // 추천 결과도 믿지 않는다. 내 카드가 아니거나 쓸 수 없는 카드면 엔진 계약 위반이다. 사용자가 보내지 않은
            // userCardId로 400을 내지 않고 서버 오류로 드러낸다(!126 리뷰).
            card = cards.findRegisteredCard(authenticatedUserId, cardId)
                    .orElseThrow(() -> new IllegalStateException("추천 카드가 사용자의 카드가 아니다: " + cardId));
            UserCardRef recommended = card;
            ineligibleReason(recommended).ifPresent(reason -> {
                throw new IllegalStateException("추천 엔진이 쓸 수 없는 카드를 골랐다: " + reason);
            });
        }
        SimulationRecommender.Benefit benefit = recommendation.benefit();
        requireOwnAlternatives(authenticatedUserId, recommendation.alternatives());
        // 출금 계좌가 없는 카드는 실제 결제도 할 수 없다. 전액 즉시할인으로 빠지는 금액이 없어도 같은 422다(Codex 검토).
        if (card.linkedAccountId() == null) {
            throw insufficient("CARD_ACCOUNT_UNKNOWN", "카드의 출금 계좌를 알 수 없어요.");
        }
        // 청구 금액은 결제 승인과 같이 계산한다: 결제 금액 − 즉시할인, 0원 아래로 내려가지 않음(!126 리뷰 1번).
        long charged = terms.chargedAmount(benefit == null ? 0 : benefit.instantDiscount());
        List<BillingSchedule.Withdrawal> withdrawals = charged == 0 ? List.of()
                : withdrawals(card, purchaseDate, charged, terms);

        // 기간은 잔고예보 조회와 같은 규칙이다: until이 없으면 설정을 따르고, 기간 기준이 NEXT_INCOME이면 days는 검사만 하고 쓰지 않는다.
        // 전에는 days가 있으면 DAYS로 정해, 같은 days로 부른 잔고예보와 기간이 달랐다(S15P31E103-318).
        ForecastService.Prepared prepared = forecasts.prepare(authenticatedUserId, request.accountIds(),
                until, days, false, today);
        PurchaseSimulation simulation = PurchaseSimulator.simulate(new PurchaseSimulator.Input(
                prepared.startBalances(), prepared.events(), prepared.horizon().from(), prepared.horizon().to(),
                prepared.dailyLiving(), prepared.alertThreshold(), purchaseDate, card.linkedAccountId(), withdrawals));

        return new SimulationResponse(
                new SimulationResponse.Input(terms.amount(), request.productCategoryCode(), request.productName(),
                        selection == null ? null : selection.merchantCategoryCode(),
                        selection == null ? null : selection.merchantId(), purchaseDate, terms.plan(),
                        terms.installmentMonths(), paymentChannel),
                new SimulationResponse.Card(cardId, cardNames.name(authenticatedUserId, cardId), card.cardType(),
                        request.userCardId() == null),
                benefit == null ? null
                        : new SimulationResponse.Benefit(benefit.netBenefit(), benefit.benefits(), charged),
                simulation.schedule().stream()
                        .map(w -> new SimulationResponse.Schedule(w.date(), w.amount(), w.label())).toList(),
                simulation.comparison(), simulation.series(),
                recommendation.performanceImpact(),
                recommendation.alternatives(),
                recommendation.assumptions(),
                recommendation.missingInformation(),
                creditLimit(authenticatedUserId, card, charged),
                simulation.outsideSelectionImpact(),
                request.includeAccountDetails() ? simulation.accountComparisons() : null,
                message(simulation),
                true);
    }

    /**
     * 추천 엔진으로 카드·혜택을 정한다. 업종·브랜드가 없으면 엔진을 부르지 않는다: 카드를 골랐으면 혜택 없이 잔고만 비교하고,
     * 고르지 않았으면 추천할 수 없어 422다. 카드를 고르지 않았는데 엔진이 추천할 카드가 없어도 422다(아무 카드나 추천처럼
     * 보이지 않게, 2026-11-05·11-06 결정).
     */
    private SimulationRecommender.Recommendation recommend(long userId, SimulationRequest request, PaymentTerms terms,
            String paymentChannel, MerchantCatalogService.Selection selection, LocalDate purchaseDate, LocalDate today) {
        if (selection == null) {
            if (request.userCardId() == null) {
                throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "추천 카드를 고르려면 업종이나 브랜드를 골라 주세요.",
                        Map.of("reason", "CATEGORY_REQUIRED"));
            }
            return new SimulationRecommender.Recommendation(request.userCardId(), null, null, null, List.of(),
                    List.of(new SimulationRecommender.MissingInformation("MERCHANT_CATEGORY_MISSING",
                            "merchantCategoryCode")));
        }
        SimulationRecommender.Result result = recommender.recommend(new SimulationRecommender.Request(userId,
                request.userCardId(), terms, paymentChannel, selection.merchantId(), selection.merchantCategoryCode(),
                purchaseDate, today));
        if (result.recommendation() == null) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, unavailableMessage(result.unavailableReason()),
                    Map.of("reason", result.unavailableReason()));
        }
        return result.recommendation();
    }

    private static String unavailableMessage(String reason) {
        return switch (reason) {
            case "NO_ELIGIBLE_CARD" -> "지금 이 구매에 쓸 수 있는 카드가 없어요. 카드를 직접 골라 주세요.";
            case "BENEFIT_DATA_INSUFFICIENT" -> "카드 혜택 정보가 부족해 추천할 수 없어요. 카드를 직접 골라 주세요.";
            case "PAYMENT_AVAILABILITY_UNKNOWN" -> "지금 카드 결제 가능 여부(잔액·한도)를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
            default -> "추천 카드를 지금 계산할 수 없어요. 카드를 직접 골라 주세요.";
        };
    }

    /**
     * 추천 엔진이 돌려준 대안 카드도 믿지 않는다. 사용자 본인의 등록 카드가 아니면 남의 카드 정보를 응답에 싣지 않도록
     * 엔진 계약 위반으로 실패한다(코드 리뷰 반영, 2026-11-05).
     */
    private void requireOwnAlternatives(long userId, List<SimulationRecommender.Alternative> alternatives) {
        if (alternatives == null) {
            return;
        }
        for (SimulationRecommender.Alternative alternative : alternatives) {
            if (cards.findRegisteredCard(userId, alternative.userCardId()).isEmpty()) {
                throw new IllegalStateException("추천 엔진의 대안 카드가 사용자의 카드가 아니다: " + alternative.userCardId());
            }
        }
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

    /**
     * 사용자가 고른 카드로 결제할 수 있는지. 결제 요청과 같은 400 CARD_NOT_ELIGIBLE + reason이고(!126 리뷰), 체크카드 할부는
     * INVALID_INSTALLMENT다.
     */
    private void usableCard(UserCardRef card, PaymentTerms terms) {
        ineligibleReason(card).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.CARD_NOT_ELIGIBLE, reason.message(), Map.of("reason", reason.name()));
        });
        if (!terms.allows(card.cardType())) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "plan", "체크카드는 일시불만 가능합니다.");
        }
    }

    /**
     * 실제 결제에서 거절될 카드면 그 이유. 사용자가 꺼 둔 카드, 카드사 정지·만료 카드, 미납 청구서가 있는 신용카드(실제 승인은
     * UNPAID_BILL로 거절)다.
     */
    private Optional<IneligibleReason> ineligibleReason(UserCardRef card) {
        if (card.registrationStatus() != CardRegistrationStatus.ACTIVE) {
            return Optional.of(IneligibleReason.CARD_INACTIVE);
        }
        if (card.issuerStatus() == CardIssuerStatus.SUSPENDED) {
            return Optional.of(IneligibleReason.CARD_SUSPENDED);
        }
        if (card.issuerStatus() == CardIssuerStatus.EXPIRED) {
            return Optional.of(IneligibleReason.CARD_EXPIRED);
        }
        if (card.cardType() == CardType.CREDIT && bills.existsByUserCardIdAndStatus(card.userCardId(), BillStatus.UNPAID)) {
            return Optional.of(IneligibleReason.UNPAID_BILL);
        }
        return Optional.empty();
    }

    /** 결제 요청의 CARD_NOT_ELIGIBLE reason과 같은 이름이다. */
    enum IneligibleReason {
        CARD_INACTIVE("꺼 둔 카드예요. 카드 설정에서 다시 켜 주세요."),
        CARD_SUSPENDED("카드사에서 정지한 카드예요."),
        CARD_EXPIRED("유효기간이 지난 카드예요."),
        UNPAID_BILL("미납 청구서가 있어 결제할 수 없는 카드예요.");

        private final String message;

        IneligibleReason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    /**
     * 신용카드의 한도 확인 결과를 결제와 같은 포트({@link CreditLimitSource})에서 그대로 옮긴다. 한도를 차감하지 않고 읽기만 한다.
     * 지금 구현은 늘 SKIPPED이고, 실제 한도 구현이 생기면 따라 바뀐다(!126 리뷰). 체크카드는 null이다.
     */
    private SimulationResponse.CreditLimit creditLimit(Long userId, UserCardRef card, long principal) {
        if (card.cardType() != CardType.CREDIT) {
            return null;
        }
        CreditLimitSource.LimitCheck check = creditLimits.check(userId, card.userCardId(), principal);
        return new SimulationResponse.CreditLimit(check.result().name(), check.availableLimit());
    }

    /** 카드 출금 회차. 결제일을 몰라 일정을 만들 수 없으면 임의 날짜로 채우지 않고 422다(출금 계좌는 부르는 쪽이 확인). */
    private List<BillingSchedule.Withdrawal> withdrawals(UserCardRef card, LocalDate purchaseDate, long charged,
            PaymentTerms terms) {
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
        // 청구 금액이 1원 이상이면 회차가 하나 이상이다. 회차가 없으면 전액 즉시할인이라 전후가 같다(Codex 검토).
        if (simulation.schedule().isEmpty()) {
            return "즉시할인으로 빠지는 금액이 없어 비교 결과는 같아요.";
        }
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
