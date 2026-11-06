package com.yamu.be.payment.history;

import com.yamu.be.forecast.SpendingClassifier;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 결제 내역. API 명세 V2 「결제 내역 목록 조회」「결제 상세 조회」. */
@Tag(name = "결제 내역")
@RestController
@RequestMapping("/api/payments")
class PaymentHistoryController {

    private final PaymentHistoryService service;
    private final SpendingClassifier classifier;

    PaymentHistoryController(PaymentHistoryService service, SpendingClassifier classifier) {
        this.service = service;
        this.classifier = classifier;
    }

    @Operation(summary = "결제 내역 목록 조회",
            description = "기간·카드·상태·출처로 거른 결제 내역과 전체 합계. 기간은 한국 날짜이고 POS 요청 시각 기준이다. "
                    + "yearMonth와 from·to는 함께 쓰지 않으며, 모두 없으면 이번 달이다. 상태 기본값은 APPROVED+CANCELED이고, "
                    + "합계 금액·혜택은 승인 건만 더한다. 최근 요청 순. "
                    + "q는 가맹점명·메모 부분 일치(숫자만이면 결제 금액·승인액 일치도), categoryCode는 업종 접두어 일치, "
                    + "benefitStatus는 NONE(혜택 없음·승인 아님)·EXPECTED(확정 전 혜택 있음)·CONFIRMED(모두 확정).")
    @GetMapping
    ApiResponse<PaymentListResponse> list(@AuthenticationPrincipal AuthUser me,
            @Parameter(description = "한국 기준 월(YYYY-MM)") @RequestParam(required = false) YearMonth yearMonth,
            @Parameter(description = "한국 날짜, 포함") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "한국 날짜, 포함") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userCardId,
            @Parameter(description = "APPROVED · DECLINED · CANCELED") @RequestParam(required = false)
            PaymentStatus status,
            @RequestParam(required = false) TransactionSource source,
            @Parameter(description = "가맹점명·메모 부분 일치, 숫자만이면 금액 일치. 50자 이하") @RequestParam(required = false) String q,
            @Parameter(description = "업종 코드 접두어") @RequestParam(required = false) String categoryCode,
            @Parameter(description = "LIVING · LARGE · EXCLUDED") @RequestParam(required = false) String spendingClass,
            @Parameter(description = "NONE · EXPECTED · CONFIRMED") @RequestParam(required = false)
            BenefitStatus benefitStatus,
            @Parameter(description = "OFFLINE · ONLINE") @RequestParam(required = false) PaymentChannel channel,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page는 1 이상입니다.") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = "size는 1 이상입니다.")
            @Max(value = PageResponse.MAX_SIZE, message = "size는 100 이하입니다.") int size) {
        // 소비 분류 필터·byClass가 비지 않게, 읽기 직전에 이 사용자의 미분류 승인 결제를 분류한다(잔고예보와 같은 방식, 2026-11-06 BE 4 결정).
        // 분류는 UPDATE라 읽기 전용 트랜잭션인 service.list 밖(여기)에서 부른다. 입력 검증보다 먼저라 잘못된 요청에도 분류는 일어난다
        classifier.classify(me.userId());
        return ApiResponse.ok(service.list(me, yearMonth, from, to, userCardId, status, source,
                new PaymentHistoryService.Conditions(q, categoryCode, spendingClass, channel, benefitStatus), page, size));
    }

    @Operation(summary = "결제 상세 조회",
            description = "결제 한 건의 상태 이력·적용 혜택·한도 사용·청구 회차·환급. 없는 결제나 다른 사용자의 결제는 404 RESOURCE_NOT_FOUND.")
    @GetMapping("/{paymentId}")
    ApiResponse<PaymentDetailResponse> detail(@AuthenticationPrincipal AuthUser me, @PathVariable Long paymentId) {
        return ApiResponse.ok(service.detail(me, paymentId));
    }
}
