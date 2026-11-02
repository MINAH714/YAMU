package com.yamu.be.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 전역 에러 코드.
 *
 * <p>이름은 API 명세서에 적힌 code를 그대로 쓴다. 공통 코드는 명세서 1장 「공통 에러 코드」,
 * 기능별 코드는 각 API 페이지의 에러 표에 있다. 명세서에 없는 코드가 필요하면 명세서에 먼저 적고 추가한다.
 * 응답의 code 값은 enum 상수 이름을 그대로 쓰므로 상수 이름과 응답 코드가 어긋날 수 없다.
 *
 * <p>FE가 code로 분기하므로 한 번 배포한 코드의 이름은 바꾸지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /* 공통 (API 명세서 1장) */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다."),
    STEP_UP_REQUIRED(HttpStatus.FORBIDDEN, "본인 인증이 필요합니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 키로 다른 요청을 보낼 수 없습니다."),
    DATA_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "계산에 필요한 데이터가 부족합니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    FIN_API_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "금융망과 통신하지 못했습니다."),
    FIN_API_ERROR(HttpStatus.BAD_GATEWAY, "금융망에서 오류가 발생했습니다."),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "금융망 응답이 지연되고 있습니다."),

    /* 인증 - 로그인 준비·로그인·토큰 재발급 (API 명세 각 페이지) */
    DEVICE_NOT_REGISTERED(HttpStatus.UNAUTHORIZED, "등록되지 않았거나 해제된 기기입니다. 본인인증 후 기기를 다시 등록해 주세요."),
    DEVICE_LOCKED(HttpStatus.FORBIDDEN, "인증에 여러 번 실패해 기기가 잠겼습니다. 잠시 후 다시 시도해 주세요."),
    PIN_MISMATCH(HttpStatus.UNAUTHORIZED, "간편 비밀번호가 일치하지 않습니다."),
    SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "기기 서명을 확인하지 못했습니다."),
    CHALLENGE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 요청이 만료되었습니다. 처음부터 다시 시도해 주세요."),
    BIOMETRIC_NOT_ENABLED(HttpStatus.BAD_REQUEST, "이 기기는 생체 인증을 사용하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),

    /* 인증 - 본인 인증(step-up) 준비·검증 (API 명세 각 페이지) */
    INVALID_PURPOSE(HttpStatus.BAD_REQUEST, "본인 인증 용도가 올바르지 않습니다."),
    // 로그인은 된 상태라 401(토큰 만료·재발급)과 헷갈리지 않게 403
    STEP_UP_FAILED(HttpStatus.FORBIDDEN, "본인 인증에 실패했습니다."),

    /* 인증 - 휴대폰 본인인증(연출)·회원가입 (API 명세 각 페이지) */
    VERIFICATION_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "인증번호를 너무 자주 요청했습니다. 잠시 후 다시 시도해 주세요."),
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),
    VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "인증 시간이 지났습니다. 인증번호를 다시 요청해 주세요."),
    SIGNUP_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "가입 진행 시간이 지났습니다. 본인인증부터 다시 진행해 주세요."),
    ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 사용자입니다. 기기 재등록으로 진행해 주세요."),
    /* 인증 - 본인인증 확인 리뷰 반영·시드 사용자 전용(S15P31E103-192). 명세서 「휴대폰 본인인증 확인」에 추가 필요 */
    // 임시(10/30): 시연용 시드에 없는 사람. 회원가입 때 SSAFY 회원을 만들지 않으므로 시드 사용자만 가입할 수 있다
    DEMO_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "시연용으로 등록된 사용자가 아닙니다."),

    /* 인증 - 간편 비밀번호 설정·기기 등록 (API 명세 각 페이지). SIGNUP_STEP_INCOMPLETE는 details.nextStep */
    INVALID_PIN_FORMAT(HttpStatus.BAD_REQUEST, "간편 비밀번호는 같은 숫자 반복·연속 숫자가 아닌 숫자 6자리로 정해 주세요."),
    INVALID_PUBLIC_KEY(HttpStatus.BAD_REQUEST, "기기 키 형식이 올바르지 않습니다."),
    SIGNUP_STEP_INCOMPLETE(HttpStatus.CONFLICT, "앞 단계 가입 절차를 먼저 마쳐 주세요."),
    DEVICE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "기기는 한 대만 등록할 수 있습니다. 기존 기기를 해제한 뒤 등록해 주세요."),

    /* 계좌 - 계좌 목록·1원 인증 요청·계좌 연결 (API 명세 각 페이지. 10/30 목록 방식으로 바꿔 계좌번호 입력·예금주 코드는 뺐다) */
    ACCOUNT_NOT_FOUND_AT_BANK(HttpStatus.NOT_FOUND, "은행에서 계좌를 찾을 수 없습니다. 계좌번호를 확인해 주세요."),
    ACCOUNT_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "원화 입출금 계좌만 연결할 수 있습니다."),
    ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결한 계좌입니다."),
    // 임시(10/30): 계좌 목록의 candidateId가 없거나(10분 지남) 남의 것. 명세에 추가 필요
    ACCOUNT_CANDIDATE_EXPIRED(HttpStatus.BAD_REQUEST, "계좌 목록이 만료됐습니다. 계좌 목록을 다시 불러와 주세요."),
    // 계좌 연결과 카드 등록이 같이 쓴다(명세 코드가 같다)
    VERIFICATION_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "확인 시간이 지났습니다. 확인 단계부터 다시 진행해 주세요."),
    AUTH_REQUEST_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "1원 인증을 너무 자주 요청했습니다. 잠시 후 다시 시도해 주세요."),
    AUTH_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증코드가 일치하지 않습니다."),
    AUTH_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 시간이 지났습니다. 1원 인증을 다시 요청해 주세요."),

    /* 계좌 - 1원 인증 남용 막기·연결 재시도. 명세에 없어 임시로 추가(S15P31E103-183, 명세에 추가 필요). 429는 details.retryAfterSeconds */
    ONE_WON_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘은 이 계좌로 1원 인증을 더 요청할 수 없습니다. 내일 다시 시도해 주세요."),
    AUTH_CODE_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "인증코드를 여러 번 틀려 오늘은 이 계좌로 인증할 수 없습니다. 내일 다시 시도해 주세요."),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),

    /* 계좌 - 계좌 정보 수정 (API 명세 「계좌 정보 수정 (기준 계좌 지정)」). 없는·남의 계좌는 공통 RESOURCE_NOT_FOUND */
    PRIMARY_REQUIRED(HttpStatus.BAD_REQUEST, "기준 계좌는 하나 있어야 합니다. 다른 계좌를 기준 계좌로 정해 주세요."),

    /* 카드 - 카드 확인·카드 등록 (API 명세 각 페이지). 확인 토큰 만료는 위 VERIFICATION_TOKEN_EXPIRED */
    INVALID_CARD_NUMBER(HttpStatus.BAD_REQUEST, "카드번호는 숫자 16자리로 입력해 주세요."),
    ISSUER_NOT_SUPPORTED(HttpStatus.NOT_FOUND, "지원하지 않는 카드사·상품입니다."),
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "등록할 수 없는 카드예요. 본인 명의 카드인지 확인해 주세요."),
    CARD_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "유효기간을 확인해 주세요."),
    CARD_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 등록한 카드입니다."),
    // 임시(명세에 추가 필요): 카드사(시드)가 정한 출금 계좌·결제일로 등록한다(10/30 결정). details.missing에 빠진 값
    CARD_ACCOUNT_NOT_SET(HttpStatus.CONFLICT, "카드사에 등록된 결제 정보가 없어 이 카드는 등록할 수 없습니다."),
    // 임시(명세에 추가 필요): 카드사가 정한 계좌를 아직 YAMU에 연결하지 않음. details.bankCode·accountLast4로 어느 계좌인지 알려 준다
    CARD_ACCOUNT_NOT_LINKED(HttpStatus.CONFLICT, "이 카드의 결제 계좌를 먼저 연결해 주세요."),
    ACCOUNT_REQUIRED(HttpStatus.BAD_REQUEST, "카드 대금을 출금할 계좌를 먼저 연결해 주세요."),
    INVALID_PAYMENT_DAY(HttpStatus.BAD_REQUEST, "결제일은 1일부터 27일 사이로 정해 주세요."),

    /* 카드 - 카드 정보 수정·순서 저장·등록 해제 (API 명세 각 페이지). 결제일 범위 오류는 위 INVALID_PAYMENT_DAY */
    PAYMENT_DAY_CHANGE_LOCKED(HttpStatus.CONFLICT, "결제일은 바꾼 뒤 3개월이 지나야 다시 바꿀 수 있습니다."),
    CARD_UNREGISTERED(HttpStatus.CONFLICT, "등록 해제한 카드는 수정할 수 없습니다."),
    ORDER_INCOMPLETE(HttpStatus.BAD_REQUEST, "보유 카드 전체를 빠짐없이 한 번씩 보내 주세요."),
    CARD_HAS_OPEN_BILL(HttpStatus.CONFLICT, "출금되지 않은 청구서가 있어 카드를 해제할 수 없습니다."),

    /* 사용자 - 회원 탈퇴 (API 명세 「회원 탈퇴」). details.billIds·refundIds */
    BILL_OUTSTANDING(HttpStatus.CONFLICT, "출금되지 않은 청구서가 있어 탈퇴할 수 없습니다."),
    REFUND_PENDING(HttpStatus.CONFLICT, "아직 입금되지 않은 환급이 있어 탈퇴할 수 없습니다."),

    /* 가계부 - 항목 수정 (API 명세 「가계부 항목 수정」). 탐지 후보는 CONFIRMED·IGNORED로만 바꿀 수 있다 */
    INVALID_STATUS_CHANGE(HttpStatus.CONFLICT, "바꿀 수 없는 상태입니다."),

    /* 가계부 - 항목 수정·삭제. 실제 결제와 연결된 회차는 예정 수정으로 바꾸지 않는다. 명세서 「가계부 항목 수정」「삭제」에 추가 필요 */
    PAID_OCCURRENCE_LOCKED(HttpStatus.CONFLICT, "실제 결제와 연결된 회차가 있어 바꿀 수 없습니다."),

    /* 잔고예보 - 조회 (API 명세 「잔고예보 조회」). accountIds 누락 또는 빈 선택 */
    ACCOUNT_SELECTION_REQUIRED(HttpStatus.BAD_REQUEST, "잔고예보에 사용할 계좌를 하나 이상 선택해 주세요."),

    /* 결제 - POS 결제 요청 생성·결제 승인 요청 (API 명세 각 페이지). 금액·할부 규칙은 POL-013 */
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "결제 금액은 1원 이상 정수로 보내 주세요."),
    INVALID_INSTALLMENT(HttpStatus.BAD_REQUEST, "할부는 신용카드로 50,000원 이상, 2~12개월만 가능합니다."),

    /*
     * 결제 - 결제 승인 요청 (API 명세 「결제 승인 요청 (카드 확정)」). 없는·남의 결제·카드는 공통 RESOURCE_NOT_FOUND.
     * 같은 결제를 처리하는 중(같은 키 재전송·다른 키 동시 승인)은 공통 IDEMPOTENCY_IN_PROGRESS를 같이 쓴다(승인 API 에러 표에 추가 필요)
     */
    CARD_NOT_ELIGIBLE(HttpStatus.BAD_REQUEST, "이 카드로는 결제할 수 없습니다. 다른 카드를 골라 주세요."),
    PAYMENT_EXPIRED(HttpStatus.GONE, "결제 확정 시간이 지났습니다. 바코드를 다시 발급해 주세요."),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 결제입니다."),
    // 임시(명세 v0.3.1 보완: 재승인 최대 3회 가정, 승인 API 에러 표에 추가 필요)
    RETRY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "다른 카드로 다시 시도할 수 있는 횟수를 넘었습니다."),

    /* 결제 - 결제 취소 (API 명세 「결제 취소」). 없는·남의 결제는 공통 RESOURCE_NOT_FOUND */
    PARTIAL_CANCEL_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "부분 취소는 지원하지 않습니다. 결제 전체를 취소해 주세요."),
    PAYMENT_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 없는 결제입니다."),
    ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 결제입니다."),

    /* 공통 - Spring MVC가 분류하는 요청 오류. 명세서 1장에 추가 필요 */
    API_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 API를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),

    /* 공통 - 동시 수정(@Version) 충돌. 서비스가 더 알맞은 코드로 바꾸지 않았을 때의 기본값. 명세서 1장에 추가 필요 */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청과 겹쳤습니다. 다시 시도해 주세요."),

    /* 공통 - 호출 횟수 제한. details.retryAfterSeconds. 명세서 1장에 추가 필요 */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    /* 결제 바코드 (API 명세 「결제 바코드 발급」「바코드 상태·결제 요청 조회」「POS 결제 요청 생성」) */
    NO_ACTIVE_CARD(HttpStatus.CONFLICT, "결제에 사용할 수 있는 카드가 없습니다. 카드를 먼저 등록해 주세요."),
    BARCODE_NOT_FOUND(HttpStatus.NOT_FOUND, "바코드를 찾을 수 없습니다."),
    BARCODE_EXPIRED(HttpStatus.GONE, "만료된 바코드입니다. 앱에서 바코드를 다시 발급해 주세요."),
    BARCODE_ALREADY_USED(HttpStatus.CONFLICT, "이미 결제에 사용한 바코드입니다."),

    /* 모의 POS - 결제 요청 생성 (API 명세 「POS 결제 요청 생성」). 금액·할부 오류는 위 INVALID_AMOUNT·INVALID_INSTALLMENT */
    INVALID_POS_KEY(HttpStatus.UNAUTHORIZED, "POS 키가 없거나 올바르지 않습니다."),
    MERCHANT_NOT_FOUND(HttpStatus.NOT_FOUND, "가맹점을 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더(UUID)가 필요합니다.");

    private final HttpStatus status;
    private final String message;

    public String getCode() {
        return name();
    }
}
