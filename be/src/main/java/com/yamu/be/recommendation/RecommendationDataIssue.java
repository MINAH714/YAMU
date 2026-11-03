package com.yamu.be.recommendation;

/** 조회·정책 확인 사유. 예외 메시지나 DB 접속 정보는 외부로 전달하지 않는다. */
public record RecommendationDataIssue(Long userCardId, Long policyId, String code, boolean retryable) {
    public static final String RETRY_MESSAGE = "일시적으로 카드 정보를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.";

    /** HTTP 연결 시에도 원본 DB 예외 대신 이 안내를 전달한다. */
    @com.fasterxml.jackson.annotation.JsonProperty("message")
    public String message() {
        return switch (code) {
            case "DATABASE_READ_RETRYABLE", "RECOGNITION_AGGREGATE_READ_RETRYABLE" -> RETRY_MESSAGE;
            default -> null;
        };
    }
}
