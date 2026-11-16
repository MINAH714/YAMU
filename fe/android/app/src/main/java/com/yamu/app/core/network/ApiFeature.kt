package com.yamu.app.core.network

import com.yamu.app.BuildConfig

/**
 * 진짜 서버를 쓸지 고르는 단위 — Repository 하나가 한 칸.
 * debug는 local.properties의 yamu.realApi(쉼표로 구분)에 적은 것만 진짜, release는 전부 진짜.
 * 이름(key)은 app/build.gradle.kts의 apiFeatures와 같아야 한다.
 */
enum class ApiFeature(val key: String, val label: String) {
    AUTH("auth", "가입 · 로그인"),
    LINK("link", "계좌 · 카드 연결"),
    CASHFLOW("cashflow", "반복 수입 · 지출 확인"),
    HOME("home", "홈"),
    PAYMENT("payment", "결제"),
    PERFORMANCE("performance", "실적"),
    FORECAST("forecast", "잔고예보"),
    REPORT("report", "리포트"),
    MY("my", "마이"),
    POS("pos", "모의 POS"),
    BILLING("billing", "청구 · 환급"),
    ;

    val isReal: Boolean get() = this in real

    companion object {
        /** 이 빌드에서 진짜 서버를 쓰는 기능 */
        val real: Set<ApiFeature> by lazy { parse(BuildConfig.REAL_API) }

        fun parse(value: String): Set<ApiFeature> {
            val names = value.split(',').map { it.trim() }.toSet()
            val on = entries.filterTo(mutableSetOf()) { it.key in names }
            // 소비 리포트의 자산 흐름과 잔고예보는 같은 계좌·예보 API를 쓴다. 통계만 실제이고 잔액은 가짜인 혼합을 막는다.
            if (REPORT in on) on += FORECAST
            // 가계부의 계좌 · 카드 번호는 연결 목록에서 온다. 목록이 가짜면 가짜 번호가 진짜 POST · PATCH에 실려 같은 번호의 진짜 계좌에 붙는다
            if (CASHFLOW in on || FORECAST in on) on += LINK
            // 카드 상세 '다음 출금' → 그 카드 청구서. 카드 목록이 가짜면 가짜 카드 번호로 진짜 청구서를 찾다가 '찾을 수 없는 카드'가 뜬다
            if (BILLING in on) on += LINK
            // 결제 코드는 인증 전에 카드 목록으로 0장인지 본다(S15P31E103-450). 목록이 가짜(0장)면 진짜 카드가 있어도 '결제할 카드가 없어요'로 끝난다
            if (PAYMENT in on) on += LINK
            return on
        }
    }
}
