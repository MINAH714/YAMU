package com.yamu.be.cashflow;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 가계부 예정 수입·지출 API. 명세: 노션 API 명세서 V2 「가계부 항목 등록」「목록」「수정」「삭제」. */
@RestController
@RequestMapping("/api/cashflow-items")
class CashflowController {

    private final CashflowService service;
    private final CashflowCreator creator;

    CashflowController(CashflowService service, CashflowCreator creator) {
        this.service = service;
        this.creator = creator;
    }

    /**
     * 예정 수입·지출을 등록하고 201로 등록된 항목을 돌려준다. Idempotency-Key(UUID, 선택)를 보내면 같은 키 재요청은 처음 항목을
     * 돌려준다(S15P31E103-358).
     */
    @PostMapping
    ResponseEntity<ApiResponse<CashflowItemResponse>> create(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCashflowItemRequest request) {
        CashflowItemResponse body = CashflowItemResponse.from(
                creator.create(me.userId(), request.toCommand(), idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    /** 기간 안의 예정 항목을 돌려준다. expand(기본 true)면 반복 항목을 발생일마다 펼친다. */
    @GetMapping
    ApiResponse<CashflowItemResponse.ListResponse> list(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) CashflowItem.Direction type,
            @RequestParam(required = false) CashflowItem.Status status,
            @RequestParam(defaultValue = "true") boolean expand) {
        var items = service.list(me.userId(), from, to, type, status, expand).stream()
                .map(CashflowItemResponse::from).toList();
        return ApiResponse.ok(new CashflowItemResponse.ListResponse(items, 0));
    }

    /** 예정 항목을 고친다. THIS_ONLY는 고친 회차를, 그 외는 고친 규칙을 돌려준다. */
    @PatchMapping("/{cashflowItemId}")
    ApiResponse<CashflowItemResponse> update(@AuthenticationPrincipal AuthUser me,
            @PathVariable Long cashflowItemId, @Valid @RequestBody UpdateCashflowItemRequest request) {
        return ApiResponse.ok(CashflowItemResponse.from(service.update(me.userId(), cashflowItemId, request.toCommand())));
    }

    /** 예정 항목을 지우고 204를 돌려준다. scope 기본값은 ALL이고, THIS_ONLY는 occurrenceDate가 필요하다. */
    @DeleteMapping("/{cashflowItemId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long cashflowItemId,
            @RequestParam(defaultValue = "ALL") CashflowService.DeleteScope scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate occurrenceDate) {
        service.delete(me.userId(), cashflowItemId, scope, occurrenceDate);
        return ResponseEntity.noContent().build();
    }
}
