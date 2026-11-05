package com.yamu.be.payment.memo;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 결제 메모 요청 본문(API 명세 「결제 메모」). {@code memo}는 필수 칸이고 null·빈 문자열이면 메모를 지운다.
 * 칸이 빠진 본문과 null을 가르려고 DTO 바인딩 대신 JSON을 직접 본다(빠지면 400, null이면 삭제).
 *
 * <ul>
 *   <li>앞뒤 공백은 지운다. 공백만 있으면 삭제로 본다(2026-11-06 결정 M4).</li>
 *   <li>길이는 코드포인트 수로 센다. DB {@code VARCHAR(100)}(utf8mb4)와 같은 기준이라 이모지도 1자다(결정 M3).</li>
 * </ul>
 *
 * @param memo 저장할 메모. null이면 삭제
 */
public record PaymentMemoRequest(
        @Schema(description = "최대 100자. 빈 문자열이나 null이면 메모 삭제", example = "팀 회식 1/N 정산 예정",
                nullable = true, maxLength = PaymentMemoRequest.MAX_LENGTH, requiredMode = Schema.RequiredMode.REQUIRED)
        String memo) {

    static final int MAX_LENGTH = 100;

    public static PaymentMemoRequest parse(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode() || !body.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 본문은 JSON 객체여야 합니다.");
        }
        if (!body.has("memo")) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "memo", "memo 칸이 필요합니다. 지우려면 null이나 빈 문자열을 보내 주세요.");
        }
        JsonNode memo = body.get("memo");
        if (memo.isNull()) {
            return new PaymentMemoRequest(null);
        }
        if (!memo.isTextual()) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "memo", "메모는 문자열이어야 합니다.");
        }
        String text = memo.asText().strip();
        if (text.isEmpty()) {
            return new PaymentMemoRequest(null);
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.MEMO_TOO_LONG, Map.of("maxLength", MAX_LENGTH));
        }
        return new PaymentMemoRequest(text);
    }
}
