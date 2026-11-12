package com.yamu.app.core.designsystem.card

import androidx.annotation.DrawableRes
import com.yamu.app.R

/**
 * 카드 상품 그림 — 상품명으로 앱에 묶은 앞면 그림(res/drawable-nodpi/card_*.webp)을 찾는다.
 * 서버가 주는 imageUrl(card_product.image_key)은 아직 쓰지 않아 상품명이 열쇠다. 못 찾으면 null — 화면은 카드사 칩 · 색 카드로 떨어진다.
 * 리소스 이름은 card_ + 수집 ID(- → _). 처음 7장의 출처는 card-analysis의 data/cards.csv · sources.csv,
 * S15P31E103-438로 더한 10장은 팀 CloudFront 원본(be/seed/card-images/card-images.json의 sha256과 대조)이다.
 * 세로 원본은 반시계로 90° 돌려 칩이 왼쪽에 오는 가로(약 1.586:1)로 묶었다.
 * 리소스 줄이기(release) 때문에 R.drawable을 여기에 직접 적는다 — getIdentifier로 이름을 조합하면 그림이 빠진다.
 */
object CardImages {
    /**
     * 상품명 → 그림. 이름은 BE 시드 상품명(card-images.json product_name) — 다른 이름으로 오면 줄을 더한다(별칭).
     * 시드에서 그림이 있는 17종을 모두 둔다. 적재가 보류된 Point Plan · Deep Oil · 원더카드 2.0 DAILY는 뺐다
     */
    private val products: List<Pair<String, Int>> = listOf(
        "현대카드ZERO Edition3(포인트형)" to R.drawable.card_hyundai_mzroe3,
        "삼성 iD ON 카드" to R.drawable.card_samsung_id_on,
        "KB국민 청춘대로 톡톡카드" to R.drawable.card_kb_toktok,
        "KB국민 노리2 체크카드(KB Pay)" to R.drawable.card_kb_07964,
        "하나카드 트래블로그 체크카드" to R.drawable.card_hana_travelog_check,
        "LOCA LIKIT Play" to R.drawable.card_lotte_p13949_a13949,
        "LOCA LIKIT Shop" to R.drawable.card_lotte_p13955_a13955,
        "현대카드 ZERO Edition3 (할인형)" to R.drawable.card_hyundai_zero_edition3_discount,
        "신한카드 Mr.Life" to R.drawable.card_shinhan_mrlife,
        "신한카드 처음" to R.drawable.card_shinhan_cheoeum,
        "현대카드 M ME4" to R.drawable.card_hyundai_m,
        "카카오뱅크 프렌즈 체크" to R.drawable.card_kakaobank_friends_check,
        "KB국민 노리 체크" to R.drawable.card_kb_01664,
        "LOCA LIKIT Eat" to R.drawable.card_lotte_p13943_a13943,
        "LOCA LIKIT 2.0" to R.drawable.card_lotte_p15835_a15835,
        "삼성 iD SIMPLE" to R.drawable.card_samsung_aap1767,
        "삼성 taptap O" to R.drawable.card_samsung_taptap_o,
    )

    // 카드사 이름 머리 — '카드'를 지운 뒤에 뗀다. 긴 것부터. byKey보다 먼저 초기화돼야 한다
    private val issuerPrefixes = listOf("카카오뱅크", "kb국민", "nh농협", "신한", "국민", "하나", "현대", "삼성", "롯데", "우리", "농협", "kb", "nh")

    private val byKey: Map<String, Int> = products.associate { (name, res) -> key(name) to res }

    /** 그림 모두 — 갤러리 · 테스트용 */
    val all: List<Int> = products.map { it.second }.distinct()

    @DrawableRes
    fun forProduct(productName: String): Int? = byKey[key(productName)]

    /**
     * 비교 열쇠 — 글자 · 숫자 말고는 지우고(띄어쓰기 · 괄호 · 점 · 가운뎃점) 소문자로, '카드'를 지운 뒤 카드사 머리를 뗀다.
     * 'KB국민 노리2 체크카드(KB Pay)' · '노리2 체크카드 KB Pay' → '노리2체크kbpay'.
     * 통째로 같아야 찾는다(부분 일치 없음) — 이름이 비슷한 다른 상품(ZERO 할인형 · LIKIT Shop/Play · 노리/노리2)이 섞이지 않게
     */
    internal fun key(name: String): String {
        val s = name.lowercase().filter { it.isLetterOrDigit() }.replace("카드", "")
        val prefix = issuerPrefixes.firstOrNull { s.startsWith(it) && s.length > it.length }
        return if (prefix == null) s else s.removePrefix(prefix)
    }
}
