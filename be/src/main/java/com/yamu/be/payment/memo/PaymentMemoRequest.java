package com.yamu.be.payment.memo;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 결제 메모 요청 본문(API 명세 「결제 메모」). {@code memo}는 필수 칸이고 null·빈 문자열이면 메모를 지운다.
 * 칸이 빠진 본문과 null을 가르려고 DTO 바인딩 대신 JSON을 직접 본다(빠지면 400, null이면 삭제).
 *
 * <ul>
 *   <li>메모는 한 줄이다. 줄바꿈({@code \r\n}·{@code \n}·{@code \r}·U+2028·U+2029 등)과 탭은 공백 하나로 바꾸고,
 *       그 밖의 제어문자가 있으면 400 INVALID_REQUEST다. 메모는 짧은 한 줄로 다뤄 목록·상세 화면에 한 줄로 보이게 한다.
 *       줄바꿈은 일부러 넣기보다 붙여넣기에서 딸려 오는 경우가 많다고 봤다(2026-11-06 !140 리뷰).</li>
 *   <li>그다음 앞뒤 공백을 지운다. 공백만 있으면 삭제로 본다(2026-11-06 결정 M4).</li>
 *   <li>길이는 정리한 뒤의 코드포인트 수로 센다. DB {@code VARCHAR(100)}(utf8mb4)와 같은 기준이다(결정 M3).
 *       한글·대부분의 이모지는 1자지만, 여러 코드포인트로 된 이모지(가족·국기·피부색)는 2자 이상으로 센다.</li>
 * </ul>
 *
 * @param memo 저장할 메모. null이면 삭제
 */
public record PaymentMemoRequest(
        @Schema(description = "한 줄 메모, 최대 100자(코드포인트 기준). 줄바꿈·탭은 공백으로 바뀐다. 빈 문자열이나 null이면 메모 삭제", example = "팀 회식 1/N 정산 예정",
                nullable = true, maxLength = PaymentMemoRequest.MAX_LENGTH, requiredMode = Schema.RequiredMode.REQUIRED)
        String memo) {

    static final int MAX_LENGTH = 100;

    /** 공백 하나로 바꿀 줄바꿈·탭. {@code \r\n}은 한 덩어리로 본다. VT·FF·NEL·줄/문단 구분자도 줄바꿈으로 보인다. */
    private static final Pattern LINE_BREAK_OR_TAB = Pattern.compile("\r\n|[\n\r\t\u000B\u000C\u0085\u2028\u2029]");

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
        String oneLine = LINE_BREAK_OR_TAB.matcher(memo.asText()).replaceAll(" ");
        if (oneLine.codePoints().anyMatch(Character::isISOControl)) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "memo", "메모에 쓸 수 없는 문자가 있습니다.");
        }
        String text = oneLine.strip();
        if (text.isEmpty()) {
            return new PaymentMemoRequest(null);
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.MEMO_TOO_LONG, Map.of("maxLength", MAX_LENGTH));
        }
        return new PaymentMemoRequest(text);
    }
}
