package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.YamuJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** 결제 완료(#1 승인 응답)와 결제 상세(#3)가 적립을 같은 단위 · 같은 금액으로 보인다(S15P31E103-580) */
class PointBenefitUnitTest {

    private fun lines(benefits: String): List<String> = YamuJson.decodeFromString(
        PaymentResultDto.serializer(), """{"paymentId":1,"status":"APPROVED","amount":30000,"benefits":[$benefits]}""",
    ).toReceipt().benefits.map { it.amountText() }

    @Test fun `원화로 바꾼 적립 — 승인 응답은 krwAmount, 상세는 actualAmount로 같은 원`() {
        // 1P = 0.5원: 승인 amount는 포인트 수(3,000P)이고 원화는 1,500원
        val approval = lines("""{"name":"카페 적립","type":"POINT","amount":3000,"realization":"POINT_ACCRUAL","unit":"POINT","krwAmount":1500}""")
        val detail = lines("""{"name":"카페 적립","type":"POINT","actualAmount":1500,"realization":"POINT_ACCRUAL","rawAmount":3000,"unit":"POINT"}""")
        assertEquals(listOf("1,500원 적립"), approval)
        assertEquals(approval, detail)
    }

    @Test fun `원화로 못 바꾼 적립 — 두 화면 모두 포인트 수에 P, 상세에서 줄이 사라지지 않는다`() {
        val approval = lines("""{"name":"M포인트","type":"POINT","amount":1500,"realization":"POINT_ACCRUAL","unit":"POINT","krwAmount":null}""")
        val detail = lines("""{"name":"M포인트","type":"POINT","actualAmount":null,"realization":"POINT_ACCRUAL","rawAmount":1500,"unit":"POINT"}""")
        assertEquals(listOf("1,500P 적립"), approval)
        assertEquals(approval, detail)
    }

    @Test fun `소수 포인트는 반올림하지 않는다`() {
        assertEquals(listOf("1.5P 적립"), lines("""{"name":"적립","type":"POINT","rawAmount":1.5,"unit":"POINT"}"""))
    }

    @Test fun `옛 서버(unit 없음) 승인 응답의 적립 amount는 포인트 수라 P로`() {
        assertEquals(listOf("1,500P 적립"), lines("""{"name":"적립","type":"POINT","amount":1500,"realization":"POINT_ACCRUAL"}"""))
    }

    @Test fun `할인 · 캐시백은 전처럼 원, 0원 줄은 그리지 않는다`() {
        assertEquals(
            listOf("660원 할인", "3,180원 환급"),
            lines(
                """{"name":"즉시할인","type":"DISCOUNT","amount":660,"realization":"AT_APPROVAL","unit":"KRW","krwAmount":660},""" +
                    """{"name":"캐시백","type":"CASHBACK","actualAmount":3180,"realization":"AFTER_BILLING","rawAmount":3180,"unit":"KRW"},""" +
                    """{"name":"0원 적립","type":"POINT","amount":0,"realization":"POINT_ACCRUAL","unit":"POINT","krwAmount":0}""",
            ),
        )
    }
}
