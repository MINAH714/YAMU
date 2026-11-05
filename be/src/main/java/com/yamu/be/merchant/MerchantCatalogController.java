package com.yamu.be.merchant;

import com.yamu.be.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantCatalogController {
    private final MerchantCatalogService service;
    public MerchantCatalogController(MerchantCatalogService service) { this.service = service; }

    @Operation(summary = "가맹점 업종 목록", description = "인증 없이 조회하는 평면 업종 목록. 물품 카테고리가 아닙니다.")
    @GetMapping("/api/merchant-categories")
    public ApiResponse<List<MerchantCategories.Category>> categories() {
        return ApiResponse.ok(service.categories());
    }

    @Operation(summary = "업종별 브랜드 목록", description = "업종 코드 필수·정확 일치. 브랜드가 없으면 빈 배열을 반환합니다.")
    @GetMapping("/api/merchants")
    public ApiResponse<List<MerchantCatalogService.MerchantItem>> merchants(
            @io.swagger.v3.oas.annotations.Parameter(required = true)
            @RequestParam(required = false) String merchantCategoryCode) {
        // 누락도 서비스 검증을 거쳐 기존 fieldErrors 형식으로 반환한다.
        return ApiResponse.ok(service.merchants(merchantCategoryCode));
    }
}
