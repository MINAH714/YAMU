package com.yamu.app.core.designsystem.card

import androidx.annotation.DrawableRes
import com.yamu.app.R

/**
 * 카드 상품 그림 — 상품명으로 앱에 묶은 앞면 그림(res/drawable-nodpi/card_*.webp)을 찾는다.
 * 서버 card_product에 이미지 · 상품 코드 칸이 없어 상품명이 유일한 열쇠다(18 문서). 못 찾으면 null — 화면은 카드사 칩 · 색 카드로 떨어진다.
 * 그림 출처(카드사 공식 URL · sha256)는 card-analysis의 data/cards.csv · sources.csv, 리소스 이름은 card_ + card_id(- → _).
 * 리소스 줄이기(release) 때문에 R.drawable을 여기에 직접 적는다 — getIdentifier로 이름을 조합하면 그림이 빠진다.
 */
object CardImages {
    /** 상품명 → 그림. 이름은 카드사 공식 이름(cards.csv product_name) — 시드 이름이 다르게 정해지면 줄을 더한다(별칭) */
    private val products: List<Pair<String, Int>> = listOf(
        "현대카드ZERO Edition3(포인트형)" to R.drawable.card_hyundai_mzroe3,
        "신한카드 Point Plan" to R.drawable.card_shinhan_pointplan,
        "삼성 iD ON 카드" to R.drawable.card_samsung_id_on,
        "신한카드 Deep Oil" to R.drawable.card_shinhan_biabix,
        "KB국민 청춘대로 톡톡카드" to R.drawable.card_kb_toktok,
        "KB국민 노리2 체크카드(KB Pay)" to R.drawable.card_kb_07964,
        "하나카드 원더카드 2.0 DAILY" to R.drawable.card_hana_wonder2_daily,
        "하나카드 트래블로그 체크카드" to R.drawable.card_hana_travelog_check,
        "LOCA LIKIT Play" to R.drawable.card_lotte_p13949_a13949,
        "LOCA LIKIT Shop" to R.drawable.card_lotte_p13955_a13955,
        // 로컬 시드(S15P31E103-97) 상품명. 할인형 기본 플레이트(card_ZROE3_G1_f.png)는 포인트형과 같은 파일(sha256 f2934393…)이라 같은 그림.
        // 시드의 Mr.Life · 처음은 그림을 넣지 않아 카드사 칩으로 떨어진다
        "현대카드 ZERO Edition3 (할인형)" to R.drawable.card_hyundai_mzroe3,
    )

    // 카드사 이름 머리 — '카드'를 지운 뒤에 뗀다. 긴 것부터. byKey보다 먼저 초기화돼야 한다
    private val issuerPrefixes = listOf("kb국민", "nh농협", "신한", "국민", "하나", "현대", "삼성", "롯데", "우리", "농협", "kb", "nh")

    private val byKey: Map<String, Int> = products.associate { (name, res) -> key(name) to res }

    /** 그림 모두 — 갤러리 · 테스트용 */
    val all: List<Int> = products.map { it.second }.distinct()

    @DrawableRes
    fun forProduct(productName: String): Int? = byKey[key(productName)]

    /**
     * 비교 열쇠 — 글자 · 숫자 말고는 지우고(띄어쓰기 · 괄호 · 점 · 가운뎃점) 소문자로, '카드'를 지운 뒤 카드사 머리를 뗀다.
     * 'KB국민 노리2 체크카드(KB Pay)' · '노리2 체크카드 KB Pay' → '노리2체크kbpay'.
     * 통째로 같아야 찾는다(부분 일치 없음) — 이름이 비슷한 다른 상품(ZERO 할인형 · LIKIT Shop/Play)이 섞이지 않게
     */
    internal fun key(name: String): String {
        val s = name.lowercase().filter { it.isLetterOrDigit() }.replace("카드", "")
        val prefix = issuerPrefixes.firstOrNull { s.startsWith(it) && s.length > it.length }
        return if (prefix == null) s else s.removePrefix(prefix)
    }
}
