package com.yamu.be.recommendation;

/** 조회·정책 확인 사유. 예외 메시지나 DB 접속 정보는 외부로 전달하지 않는다. */
public record RecommendationDataIssue(Long userCardId, Long policyId, String code, boolean retryable) {
    public static final String IBK_PARTIAL_ITEMS = "IBK_PARTIAL_ITEMS";
    public static final String IBK_PARTIAL_ITEMS_NOTICE = "현장할인은 대상이 확인된 상품 금액만 반영했습니다. 바나나맛우유·컵라면·떡볶이 등 대상 여부가 미확인인 상품의 현장할인은 포함하지 않았습니다.";
    public static final String IBK_CONFIRMED_ONLY = "IBK_CONFIRMED_INSTANT_ONLY";
    public static final String IBK_CONFIRMED_NOTICE = "확인된 GS25 현장할인만 반영했습니다. 추가 환급은 계산 기준 미확정으로 포함하지 않았습니다.";
    public static final String IBK_ORIGINAL_CASHBACK = "IBK_ORIGINAL_AMOUNT_CASHBACK_POLICY";
    public static final String IBK_ORIGINAL_NOTICE = "프로젝트 정책에 따라 현장할인과 편의점 환급을 할인 전 금액으로 계산했습니다. 환급은 예상 혜택이며 즉시 출금액에서 차감하지 않습니다.";
    public static final String RETRY_MESSAGE = "일시적으로 카드 정보를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.";

    /** HTTP 연결 시에도 원본 DB 예외 대신 이 안내를 전달한다. */
    @com.fasterxml.jackson.annotation.JsonProperty("message")
    public String message() {
        if (code.startsWith("IBK_ITEM_CLASSIFICATION_UNKNOWN:")) {
            String name = switch (code.substring("IBK_ITEM_CLASSIFICATION_UNKNOWN:".length())) {
                case "gs-banana-milk" -> "바나나맛우유";
                case "gs-cup-ramen" -> "컵라면";
                case "gs-rose-tteokbokki" -> "떡볶이";
                default -> "구매 상품";
            };
            return name + "의 GS25 현장할인 대상 여부가 아직 확인되지 않아 해당 상품의 현장할인을 계산하지 못했어요. 할인 제외가 확정된 상품은 아니에요.";
        }
        if (code.startsWith("IBK_UNSUPPORTED_")) return "이 거래에 관련된 미지원 혜택이 있어 전체 혜택을 확정할 수 없습니다: " + code.substring(16);
        return switch (code) {
            case IBK_PARTIAL_ITEMS -> IBK_PARTIAL_ITEMS_NOTICE;
            case IBK_CONFIRMED_ONLY -> IBK_CONFIRMED_NOTICE;
            case IBK_ORIGINAL_CASHBACK -> IBK_ORIGINAL_NOTICE;
            case "IBK_ISSUER_REGISTRATION_UNKNOWN" -> "카드사 사용 등록일을 확인하지 못했습니다.";
            case "IBK_INTRODUCTORY_WAIVER_EXCLUDED" -> "신규 발급 우대기간은 이번 계산 범위에 포함되지 않습니다.";
            case "IBK_MONTHLY_ELIGIBILITY_UNKNOWN" -> "해당 이용월의 병 급여·현역 자격을 확인하지 못했습니다.";
            case "IBK_MILITARY_PACKAGE_UNSUPPORTED" -> "병 급여·현역병 우대는 현재 지원하지 않습니다.";
            case "IBK_PERFORMANCE_POSTING_UNSUPPORTED" -> "IBK 실적 인정 및 취소 접수월 차감은 아직 지원하지 않습니다.";
            case "IBK_PREVIOUS_PERFORMANCE_UNKNOWN" -> "IBK 전월 실적을 확인하지 못했습니다.";
            case "IBK_DAILY_COUNT_CONFLICT" -> "편의점 일 할인 횟수가 공식 안내 간 달라 미확인입니다.";
            case "IBK_STACKED_BASE_UNKNOWN" -> "현장할인과 환급 중복 시 환급 계산 기준액이 미확인입니다.";
            case "IBK_ROUNDING_AND_REVERSAL_UNKNOWN" -> "추가 혜택의 절사·계산 순서와 취소 후 한도 복원 기준이 미확인입니다.";
            case "IBK_REGION_UNKNOWN_OR_UNSUPPORTED" -> "국내 일반 구매만 계산하며 해외·지역 미확인 거래는 지원하지 않습니다.";
            case "IBK_MEDICAL_CLASSIFICATION_UNKNOWN" -> "해당 의료 업종의 IBK 대상 분류를 확인하지 못했습니다.";
            case "DATABASE_READ_RETRYABLE", "RECOGNITION_AGGREGATE_READ_RETRYABLE", "BALANCE_READ_RETRYABLE" -> RETRY_MESSAGE;
            default -> null;
        };
    }
}
