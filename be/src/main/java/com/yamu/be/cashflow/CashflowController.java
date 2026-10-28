package com.yamu.be.cashflow;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 가계부 예정 수입·지출 등록·목록 API. 명세: 노션 API 명세서 V2 「가계부 항목 등록」「가계부 항목 목록」. */
@RestController
@RequestMapping("/api/cashflow-items")
class CashflowController {

    private final CashflowService service;

    CashflowController(CashflowService service) {
        this.service = service;
    }

    /** 예정 수입·지출을 등록하고 201로 등록된 항목을 돌려준다. */
    @PostMapping
    ResponseEntity<ApiResponse<CashflowItemResponse>> create(@AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody CreateCashflowItemRequest request) {
        CashflowItemResponse body = CashflowItemResponse.from(service.create(me.userId(), request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    /** 기간과 겹치는 예정 규칙 목록을 돌려준다. expand는 Task 7.1.3 전까지 지원하지 않는다. */
    @GetMapping
    ApiResponse<CashflowItemResponse.ListResponse> list(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) CashflowItem.Direction type,
            @RequestParam(required = false) CashflowItem.Status status) {
        var items = service.listRules(me.userId(), from, to, type, status).stream()
                .map(CashflowItemResponse::from).toList();
        return ApiResponse.ok(new CashflowItemResponse.ListResponse(items, 0));
    }
}
