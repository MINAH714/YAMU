package com.yamu.be.payment.history;

/**
 * 결제 원장을 읽는 SQL 조각. 결제 내역({@link PaymentHistoryReader})과 소비 리포트가 같은 판정을 쓰게 한 곳에 둔다.
 * 혜택 합계·확정 판정이 두 화면에서 다르게 나오지 않도록, 조각을 고치면 두 곳이 함께 바뀐다.
 *
 * <p>모든 조각은 결제 행의 별칭을 {@code p}, 가맹점 행의 별칭을 {@code m}으로 가정한다.
 */
public final class PaymentSqlFragments {

    /** 결제 당시 가맹점 이름. 스냅샷이 없으면 가맹점 표의 브랜드명이다. */
    public static final String MERCHANT_NAME =
            "coalesce(nullif(p.merchant_context_snapshot ->> '$.merchantName', 'null'), m.merchant_name)";
    /** 결제 당시 업종 코드. 스냅샷이 없으면 가맹점 표의 업종 코드다. */
    public static final String CATEGORY_CODE =
            "coalesce(nullif(p.merchant_context_snapshot ->> '$.categoryCode', 'null'), m.merchant_category_code)";

    /**
     * 혜택 줄이 확정됐는지(2026-11-06 결정, {@link BenefitStatus}). 즉시할인은 승인 때, 청구할인은 결제의 회차가 모두 청구 확정되면
     * (청구 전 PENDING 회차가 없으면) 확정이다. 캐시백·적립·시점 미확인은 기본적으로 확정되지 않는다.
     * 등록된 LIFE 시드에만 QA_HISTORY 확정 근거를 허용하며 일반 결제는 이 예외를 적용하지 않는다.
     * 임시: 할부 청구할인은 회차가 일부만 확정된 동안 확정으로 세지 않는다(회차별 배분은 S15P31E103-299의 bill_item_discount).
     */
    public static final String CONFIRMED_LINE = """
            ((b.confirmed_at IS NOT NULL AND b.confirmation_source = 'QA_HISTORY'
              AND EXISTS (SELECT 1 FROM payment_transaction qp JOIN qa_seed_member qm ON qm.user_id=qp.user_id
                          WHERE qp.payment_transaction_id=b.payment_transaction_id
                            AND JSON_UNQUOTE(JSON_EXTRACT(qp.merchant_context_snapshot,'$.source'))='QA_LIFE_550'))
             or cb.application_timing = 'AT_APPROVAL'
             or (cb.application_timing = 'AT_BILLING'
                 and exists (select 1 from bill_item i where i.payment_transaction_id = b.payment_transaction_id)
                 and not exists (select 1 from bill_item i
                                 where i.payment_transaction_id = b.payment_transaction_id and i.status = 'PENDING')))
            """;
    /** 결제의 혜택 줄 수(0원 줄 제외). */
    public static final String BENEFIT_LINES = "(select count(*) from payment_benefit b"
            + " where b.payment_transaction_id = p.payment_transaction_id and b.benefit_value > 0)";
    /** 그중 확정된 줄 수. */
    public static final String CONFIRMED_LINES = "(select count(*) from payment_benefit b"
            + " join card_benefit cb on cb.card_benefit_id = b.card_benefit_id"
            + " where b.payment_transaction_id = p.payment_transaction_id and b.benefit_value > 0 and " + CONFIRMED_LINE + ")";
    /** 확정된 혜택의 원화 합계. */
    public static final String CONFIRMED_AMOUNT = "(select coalesce(sum(b.krw_amount), 0) from payment_benefit b"
            + " join card_benefit cb on cb.card_benefit_id = b.card_benefit_id"
            + " where b.payment_transaction_id = p.payment_transaction_id and " + CONFIRMED_LINE + ")";
    /** 혜택 원화 합계. 혜택 줄이 없으면 NULL이다. */
    public static final String BENEFIT_AMOUNT = "(select sum(b.krw_amount) from payment_benefit b"
            + " where b.payment_transaction_id = p.payment_transaction_id)";
    /**
     * 결제의 대표 혜택 종류(목록 {@code benefitType}, S15P31E103-389). 원화 금액이 가장 큰 혜택 줄(같으면 먼저 기록한 줄, 원화로 바꾸지 못한
     * 줄은 맨 뒤)의 종류다. 할인은 반영 시점으로 나눠 INSTANT_DISCOUNT(승인 때)·BILLING_DISCOUNT(청구 때)로, 캐시백·적립은
     * CASHBACK·POINT로 쓴다. 대표 줄이 시점을 모르는 할인(약관 미확인)이면 시점을 지어내지 않고 NULL이다(2026-11-16 결정).
     * 혜택 줄이 없으면 NULL이다.
     */
    public static final String MAIN_BENEFIT_TYPE = """
            (select case
                        when cb.benefit_type = 'DISCOUNT' and cb.application_timing = 'AT_APPROVAL' then 'INSTANT_DISCOUNT'
                        when cb.benefit_type = 'DISCOUNT' and cb.application_timing = 'AT_BILLING' then 'BILLING_DISCOUNT'
                        when cb.benefit_type in ('CASHBACK', 'POINT') then cb.benefit_type
                    end
               from payment_benefit b
               join card_benefit cb on cb.card_benefit_id = b.card_benefit_id
              where b.payment_transaction_id = p.payment_transaction_id and b.benefit_value > 0
              order by b.krw_amount is null, b.krw_amount desc, b.payment_benefit_id
              limit 1)""";
    /** 원화로 환산되지 않은 혜택 줄 수(0원 줄 제외). */
    public static final String UNCONVERTED_LINES = "(select count(*) from payment_benefit b"
            + " where b.payment_transaction_id = p.payment_transaction_id and b.benefit_value > 0 and b.krw_amount is null)";
    /**
     * 추천 1순위로 승인했는지. 승인 때 순위를 적은 결제({@code user_selected}가 있음)는 그 순위가 1인지로 본다. 결제 상세의
     * {@code recommendedRank}와 같은 기준이다(추천 스냅샷이 만료돼 승인 때 다시 계산했으면 저장된 추천 결과와 1순위가 다를 수 있다).
     * 순위 칸이 생기기 전(V20261109_4) 결제만 저장된 추천 결과의 첫 후보와 비교한다. 둘 다 없는 결제(SEED)는 아니다
     */
    public static final String YAMU_PICK = "(p.status = 'APPROVED' and p.user_card_id is not null"
            + " and (p.recommended_rank = 1 or (p.user_selected is null"
            + " and cast(json_unquote(json_extract(p.recommendation_result_json, '$.candidates[0].card.userCardId'))"
            + " as unsigned) = p.user_card_id)))";

    private PaymentSqlFragments() {
    }
}
