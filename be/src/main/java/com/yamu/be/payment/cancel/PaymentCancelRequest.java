package com.yamu.be.payment.cancel;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 결제 취소 요청 본문(API 명세 「결제 취소」). 전체 취소만 있으므로 금액을 받지 않는다.
 * 본문에 취소 금액 칸이 있으면 400 {@code PARTIAL_CANCEL_NOT_SUPPORTED}라서 요청 DTO 바인딩 대신 JSON을 직접 본다.
 *
 * <p>사유는 저장할 칸이 없어 받기만 하고 남기지 않는다(로그에도 쓰지 않는다. 사용자가 개인정보를 적을 수 있다).
 *
 * @param reason 취소 사유. 없으면 null
 */
public record PaymentCancelRequest(
        @Schema(description = "취소 사유(저장하지 않음)", example = "고객 요청", nullable = true, maxLength = 100)
        String reason) {

    /** 취소 금액으로 읽힐 칸. 명세는 "본문에 취소 금액을 보냄"만 적었으므로 흔한 이름을 막는다(임시). */
    static final List<String> AMOUNT_FIELDS = List.of("amount", "cancelAmount");
    static final String PARTIAL_REASON = "부분 취소는 지원하지 않아 취소 금액을 보낼 수 없습니다.";
    /** 임시: 명세에 길이 제한이 없어 메모(100자)와 맞췄다 */
    static final int REASON_MAX_LENGTH = 100;

    public static PaymentCancelRequest parse(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return new PaymentCancelRequest(null);
        }
        if (!body.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 본문은 JSON 객체여야 합니다.");
        }
        List<Map<String, String>> fieldErrors = new ArrayList<>();
        for (String field : AMOUNT_FIELDS) {
            if (body.has(field)) {
                fieldErrors.add(Map.of("field", field, "reason", PARTIAL_REASON));
            }
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.PARTIAL_CANCEL_NOT_SUPPORTED, Map.of("fieldErrors", fieldErrors));
        }
        JsonNode reason = body.get("reason");
        if (reason == null || reason.isNull()) {
            return new PaymentCancelRequest(null);
        }
        if (!reason.isTextual()) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "reason", "취소 사유는 문자열이어야 합니다.");
        }
        if (reason.asText().length() > REASON_MAX_LENGTH) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "reason",
                    "취소 사유는 " + REASON_MAX_LENGTH + "자 이하로 적어 주세요.");
        }
        return new PaymentCancelRequest(reason.asText());
    }
}
