package com.yamu.be.cashflow;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.json.IntegralNumberDeserializers;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예정 지출·실제 결제 매칭 API. 명세: 노션 API 명세 「매칭 확정」「매칭 해제」「매칭 상태 조회」「매칭 후보 조회」.
 */
@RestController
class PaymentMatchController {

    private final PaymentMatchService service;

    PaymentMatchController(PaymentMatchService service) {
        this.service = service;
    }

    /** 회차와 결제를 연결한다. 새로 연결하면 201, 같은 쌍을 다시 보내면 기존 연결을 200으로 돌려준다. */
    @PostMapping("/api/cashflow-payment-matches")
    ResponseEntity<ApiResponse<PaymentMatchResponse.Confirmed>> confirm(@AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody ConfirmRequest request) {
        PaymentMatchService.Result result = service.confirm(me.userId(), request.cashflowItemId(),
                request.occurrenceDate(), request.paymentId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.body()));
    }

    /** 연결을 지우고 204를 돌려준다. */
    @DeleteMapping("/api/cashflow-payment-matches/{matchId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long matchId) {
        service.delete(me.userId(), matchId);
        return ResponseEntity.noContent().build();
    }

    /** 회차의 연결 상태를 돌려준다. 연결이 없으면 match가 null이다. */
    @GetMapping("/api/cashflow-payment-matches")
    ApiResponse<PaymentMatchResponse.Status> status(@AuthenticationPrincipal AuthUser me,
            @RequestParam Long cashflowItemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate occurrenceDate) {
        return ApiResponse.ok(service.status(me.userId(), cashflowItemId, occurrenceDate));
    }

    /** 회차와 연결할 만한 결제 후보를 돌려준다. 저장하지 않는다. */
    @GetMapping("/api/cashflow-items/{cashflowItemId}/payment-match-candidates")
    ApiResponse<PaymentMatchResponse.Candidates> candidates(@AuthenticationPrincipal AuthUser me,
            @PathVariable Long cashflowItemId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate occurrenceDate) {
        return ApiResponse.ok(service.candidates(me.userId(), cashflowItemId, occurrenceDate));
    }

    /** ID 칸에 소수(1.9)가 오면 잘라서 다른 항목·결제와 연결하지 않고 400으로 거절한다. */
    record ConfirmRequest(
            @NotNull(message = "연결할 예정 지출을 골라 주세요.") @Positive(message = "예정 지출 ID가 올바르지 않습니다.")
            @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
            Long cashflowItemId,
            @NotNull(message = "연결할 회차 날짜를 보내 주세요.") LocalDate occurrenceDate,
            @NotNull(message = "연결할 결제를 골라 주세요.") @Positive(message = "결제 ID가 올바르지 않습니다.")
            @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
            Long paymentId) {
    }
}
