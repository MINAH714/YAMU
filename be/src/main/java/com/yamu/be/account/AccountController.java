package com.yamu.be.account;

import com.yamu.be.account.dto.AccountListResponse;
import com.yamu.be.account.dto.AccountResponse;
import com.yamu.be.account.dto.AccountUpdateRequest;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 연결 계좌 목록·정보 수정. Access Token만 받는다(가입 진행 토큰으로는 계좌 연결 단계만 쓴다). */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountManagementService managementService;

    @GetMapping
    public ApiResponse<AccountListResponse> list(@AuthenticationPrincipal AuthUser me,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(managementService.list(me, refresh));
    }

    @PatchMapping("/{accountId}")
    public ApiResponse<AccountResponse> update(@AuthenticationPrincipal AuthUser me, @PathVariable Long accountId,
            @Valid @RequestBody AccountUpdateRequest request) {
        return ApiResponse.ok(managementService.update(me, accountId, request));
    }
}
