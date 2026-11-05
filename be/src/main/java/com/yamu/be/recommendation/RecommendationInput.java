package com.yamu.be.recommendation;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 서버가 확인한 값의 내부 전달 형식. HTTP DTO나 저장 JSON 계약이 아니다. */
public record RecommendationInput(
        Long paymentId, long userId, Transaction transaction,
        Long defaultUserCardId, List<CardCandidate> cards) {

    public RecommendationInput(long paymentId, long userId, Transaction transaction,
            Long defaultUserCardId, List<CardCandidate> cards) {
        this(Long.valueOf(paymentId), userId, transaction, defaultUserCardId, cards);
    }

    public RecommendationInput {
        if ((paymentId != null && paymentId <= 0) || userId <= 0 || (defaultUserCardId != null && defaultUserCardId <= 0)) {
            throw new IllegalArgumentException("결제·사용자·기본 카드 ID는 양수여야 합니다.");
        }
        Objects.requireNonNull(transaction);
        cards = List.copyOf(cards);
        if (cards.stream().map(CardCandidate::userCardId).distinct().count() != cards.size()) {
            throw new IllegalArgumentException("같은 보유 카드를 중복 전달할 수 없습니다.");
        }
        if (cards.stream().anyMatch(card -> card.userId() != userId)) {
            throw new IllegalArgumentException("다른 사용자의 카드를 계산할 수 없습니다.");
        }
    }

    public enum Plan { LUMP_SUM, INSTALLMENT }
    public enum CardType { CREDIT, CHECK }
    public enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

    /** facts는 서버가 검증한 CONDITION 코드별 사실. 키 부재는 false가 아니라 미확인이다. */
    public record Transaction(long amount, Plan plan, int installmentMonths, Instant occurredAt,
                              Long merchantId, String merchantCategoryCode, Map<String, Boolean> facts,
                              String purchaseItemCode, List<SimulationRule> simulationRules) {
        public Transaction(long amount, Plan plan, int installmentMonths, Instant occurredAt,
                Long merchantId, String merchantCategoryCode, Map<String, Boolean> facts, String purchaseItemCode) {
            this(amount,plan,installmentMonths,occurredAt,merchantId,merchantCategoryCode,facts,purchaseItemCode,List.of());
        }
        public Transaction(long amount, Plan plan, int installmentMonths, Instant occurredAt,
                Long merchantId, String merchantCategoryCode, Map<String, Boolean> facts) {
            this(amount, plan, installmentMonths, occurredAt, merchantId, merchantCategoryCode, facts, null);
        }
        public Transaction {
            simulationRules = List.copyOf(simulationRules);
            if (purchaseItemCode != null && !purchaseItemCode.matches("[A-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException("구매 항목 코드 형식 오류");
            }
            Objects.requireNonNull(plan);
            Objects.requireNonNull(occurredAt);
            facts = Map.copyOf(facts);
            if (amount <= 0) {
                throw new IllegalArgumentException("결제 원금은 양수여야 합니다.");
            }
            if ((plan == Plan.LUMP_SUM && installmentMonths != 1)
                    || (plan == Plan.INSTALLMENT
                    && (installmentMonths < 2 || installmentMonths > 12 || amount < 50_000))) {
                throw new IllegalArgumentException("저장된 결제 방식·금액·개월 수가 POL-013과 맞지 않습니다.");
            }
            if (merchantId != null && merchantId <= 0) {
                throw new IllegalArgumentException("브랜드 ID는 양수여야 합니다.");
            }
        }
    }

    /**
     * 잔액·가용 한도·미납의 판정은 담당 기능이 공급한다. 여기서는 예약·차감하지 않는다.
     * displayOrder는 저장 순서(미지정 null), registeredAt은 UTC 등록 시각이다.
     * 고정 정렬은 카드 관리 목록과 같은 지정 순서 우선 → 등록 시각 → ID다.
     */
    public record CardCandidate(long userCardId, long userId, CardType cardType,
                                CardRegistrationStatus registrationStatus, CardIssuerStatus issuerStatus,
                                Availability availability, String availabilityReason,
                                BenefitPolicy policy, PerformanceProgress performance,
                                Integer displayOrder, LocalDateTime registeredAt) {
        public CardCandidate {
            if (userCardId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("카드·사용자 ID는 양수여야 합니다.");
            }
            // 상품 조회 누락은 종류 미확인으로 보존한다. Selector가 정상 계산을 막는다.
            Objects.requireNonNull(registrationStatus);
            Objects.requireNonNull(issuerStatus);
            Objects.requireNonNull(availability);
            if (displayOrder != null && displayOrder < 0) {
                throw new IllegalArgumentException("카드 저장 순서는 음수일 수 없습니다.");
            }
            if (availability != Availability.AVAILABLE
                    && (availabilityReason == null || availabilityReason.isBlank())) {
                throw new IllegalArgumentException("결제 불가·미확인 사유가 필요합니다.");
            }
        }
    }

    /** 혜택 판단용 전월 실적과 별개. 입력 생성 계층이 period의 누적액·최소 기준·거래 인정액을 묶는다. */
    public record PerformanceProgress(YearMonth period, Long beforeAmount, Long minimumAmount, Long recognizedAmount) {
        public PerformanceProgress {
            if ((beforeAmount != null && beforeAmount < 0) || (minimumAmount != null && minimumAmount < 0)
                    || (recognizedAmount != null && recognizedAmount < 0)) {
                throw new IllegalArgumentException("실적 금액은 음수일 수 없습니다.");
            }
        }

        public boolean isKnown() {
            // 확인된 최소 기준 0원에는 신규 충족이 없다. 미확인 금액을 0으로 대체하는 것은 아니다.
            return Long.valueOf(0L).equals(minimumAmount) || period != null
                    && beforeAmount != null && minimumAmount != null && recognizedAmount != null;
        }

        public boolean newlyMeetsMinimum() {
            if (!isKnown()) {
                throw new IllegalStateException("실적 보조 정보가 미확인입니다.");
            }
            if (minimumAmount == 0) {
                return false;
            }
            // 덧셈 오버플로 없이 before < minimum <= before + recognized를 검사한다.
            return beforeAmount < minimumAmount && recognizedAmount >= minimumAmount - beforeAmount;
        }
    }
}
