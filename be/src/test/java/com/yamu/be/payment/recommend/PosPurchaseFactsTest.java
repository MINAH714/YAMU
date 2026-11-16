package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.merchant.MerchantCategories;
import org.junit.jupiter.api.Test;

/** 업종별 거래 사실({@link PosPurchaseFacts#forCategory}). DB 없음(2026-11-08, !180 리뷰). */
class PosPurchaseFactsTest {

    @Test
    void 일반_업종은_구매_사실만_공유하고_Npay_비사용을_추정하지_않는다() {
        var expected = new java.util.HashMap<>(PosPurchaseFacts.verified());
        assertThat(expected.remove("PAYMENT_METHOD:NPAY")).isFalse();
        assertThat(PosPurchaseFacts.forCategory("GAS_STATION")).isEqualTo(expected).doesNotContainKey("PAYMENT_METHOD:NPAY");
        assertThat(PosPurchaseFacts.forCategory("TELECOMMUNICATION")).isEqualTo(expected).doesNotContainKey("PAYMENT_METHOD:NPAY");
    }

    @Test
    void 사실을_확정할_수_없는_업종이나_모르는_업종이면_빈_Map이다() {
        // 지금 시드에는 없지만 전기요금 브랜드가 생기면 ELECTRICITY_BILL=false를 확정할 수 없다
        assertThat(PosPurchaseFacts.forCategory("ELECTRIC_UTILITY")).isEmpty();
        assertThat(PosPurchaseFacts.forCategory("TAXI")).isEmpty();
        // 학원비가 학교 납입금(SCHOOL_PAYMENT)에 들어가는지 원문이 없어 확정하지 않는다
        assertThat(PosPurchaseFacts.forCategory("ACADEMY")).isEmpty();
        assertThat(PosPurchaseFacts.forCategory(null)).isEmpty();
    }

    @Test
    void 사실을_확정할_수_없는_업종은_모두_지원하는_업종_코드다() {
        // 오타가 있으면 그 업종 가맹점에 사실이 조용히 넘어간다
        var excluded = MerchantCategories.list().stream().filter(c -> !c.simulationSupported()).toList();
        assertThat(excluded).hasSize(14).allSatisfy(c ->
                assertThat(PosPurchaseFacts.forCategory(c.merchantCategoryCode())).isEmpty());
    }
}
