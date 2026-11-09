package com.yamu.be.home;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈 요약·금융 데이터 동기화 API. 명세: 노션 API 명세서 V2 #58 「홈 요약」, #76 「금융 데이터 동기화」.
 * 응답 모양은 앱 {@code HomeDto.kt}(fe/android)에 맞췄다(명세와 다른 점은 MR S15P31E103-378 설명).
 */
@RestController
@RequestMapping("/api")
class HomeController {

    private final HomeService homes;
    private final SyncService syncs;

    HomeController(HomeService homes, SyncService syncs) {
        this.homes = homes;
        this.syncs = syncs;
    }

    /** 홈 요약. 영역 하나가 실패해도 200이고, 실패한 영역은 partialErrors에 적는다. */
    @GetMapping("/home")
    ApiResponse<HomeResponse> home(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(homes.home(me));
    }

    /** 연결 계좌 잔액을 금융망에서 새로 읽는다. 30초 안에 다시 부르면 지난 결과를 준다. */
    @PostMapping("/sync")
    ApiResponse<SyncResponse> sync(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(syncs.sync(me.userId()));
    }
}
