package com.yamu.be.payment.approve;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 승인 요청 본문(API 명세 「결제 승인 요청 (카드 확정)」). 앱은 카드 선택·승인 의사만 전달한다.
 *
 * <p>결제 방식·할부 개월 수는 POS가 정해 서버에 저장된 값을 쓴다. 본문에 {@code plan}·{@code installmentMonths} 칸이 있으면
 * 값이 같거나 null이어도 400 {@code INVALID_INSTALLMENT}로 거절한다(10/29 결정). 그래서 요청 DTO 바인딩 대신 JSON을 직접 본다.
 *
 * @param userCardId 결제할 카드. 생략하면 1순위 추천 카드
 */
public record PaymentApprovalRequest(
        @Schema(description = "결제할 카드. 생략하면 1순위 추천 카드로 승인한다", example = "15", nullable = true)
        Long userCardId) {

    static final List<String> POS_ONLY_FIELDS = List.of("plan", "installmentMonths");
    static final String POS_ONLY_REASON = "결제 방식과 할부 개월 수는 POS가 정한 값을 씁니다. 승인 요청에서는 보낼 수 없습니다.";

    /** 본문이 없거나 {@code null}이면 카드 생략으로 본다. */
    public static PaymentApprovalRequest parse(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return new PaymentApprovalRequest(null);
        }
        if (!body.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 본문은 JSON 객체여야 합니다.");
        }
        List<Map<String, String>> fieldErrors = new ArrayList<>();
        for (String field : POS_ONLY_FIELDS) {
            if (body.has(field)) {
                fieldErrors.add(Map.of("field", field, "reason", POS_ONLY_REASON));
            }
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INSTALLMENT, POS_ONLY_REASON, Map.of("fieldErrors", fieldErrors));
        }
        JsonNode card = body.get("userCardId");
        if (card == null || card.isNull()) {
            return new PaymentApprovalRequest(null);
        }
        if (!card.isIntegralNumber() || !card.canConvertToLong() || card.asLong() <= 0) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "userCardId", "카드 번호는 양의 정수여야 합니다.");
        }
        return new PaymentApprovalRequest(card.asLong());
    }

    /** 사용자가 카드를 직접 골랐는지(응답 {@code selectedCard.userSelected}). */
    public boolean userSelected() {
        return userCardId != null;
    }
}
