package com.yamu.be.payment.reject;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 결제 요청 거절 본문(S15P31E103-360). 앱은 {@code {"reason":"USER_REJECTED"}}를 보낸다.
 * 사유는 저장할 칸이 없어 남기지 않고, 약속한 값만 받는다. 본문이 없어도 된다.
 *
 * @param reason 거절 사유. 없으면 null
 */
public record PaymentRejectRequest(
        @Schema(description = "거절 사유(저장하지 않음). 보내면 USER_REJECTED만 받는다", example = "USER_REJECTED",
                nullable = true, allowableValues = {"USER_REJECTED"})
        String reason) {

    static final String USER_REJECTED = "USER_REJECTED";

    static PaymentRejectRequest parse(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return new PaymentRejectRequest(null);
        }
        if (!body.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 본문은 JSON 객체여야 합니다.");
        }
        JsonNode reason = body.get("reason");
        if (reason == null || reason.isNull()) {
            return new PaymentRejectRequest(null);
        }
        if (!reason.isTextual() || !USER_REJECTED.equals(reason.asText())) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "reason", "거절 사유는 USER_REJECTED만 보낼 수 있습니다.");
        }
        return new PaymentRejectRequest(USER_REJECTED);
    }
}
