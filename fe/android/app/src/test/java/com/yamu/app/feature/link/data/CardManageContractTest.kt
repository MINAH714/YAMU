package com.yamu.app.feature.link.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 요청 · 응답 JSON이 BE CardUpdateRequest · CardOrderRequest · CardDetailResponse와 같은지 */
class CardManageContractTest {
    private inline fun <reified T> fields(value: T) = YamuJson.parseToJsonElement(YamuJson.encodeToString(value)).jsonObject

    private val creditJson = """
        {"userCardId":21,"nickname":"KB국민 청춘대로 톡톡카드","issuer":{"code":"KB","name":"KB국민카드","color":"#FFBC00","closingDayOffset":14},
         "productName":"KB국민 청춘대로 톡톡카드","cardType":"CREDIT","maskedNumber":"9410-****-****-1021","expiryDate":null,
         "status":"ACTIVE","issuerStatus":"NORMAL","isDefault":true,"linkedAccount":null,
         "billing":{"paymentDay":14,"paymentDayChangeableFrom":null,"settlementAccountId":5,"creditLimit":null,"availableLimit":null,"currentCycle":null,
                    "nextWithdrawal":{"billId":7,"amount":32000,"withdrawalDate":"2026-11-14","status":"CONFIRMED"},"hasUnpaidBill":false},
         "registeredAt":"2026-11-03T01:00:00Z","dataSyncedAt":null,"imageUrl":null}
    """.trimIndent()

    private val checkJson = """
        {"userCardId":22,"nickname":"생활비","issuer":{"code":"KB","name":"KB국민카드","color":"#FFBC00","closingDayOffset":null},
         "productName":"KB국민 노리2 체크카드","cardType":"CHECK","maskedNumber":"9410-****-****-8823",
         "status":"INACTIVE","issuerStatus":"SUSPENDED","isDefault":false,
         "linkedAccount":{"accountId":5,"bankCode":"088","bankName":"신한은행","maskedAccountNo":"****-****-****-1234","balance":null,"balanceAsOf":null},
         "billing":null}
    """.trimIndent()

    @Test fun `수정 요청은 넘긴 값만 — null은 보내지 않아 서버가 그대로 둔다`() {
        assertEquals(setOf("isDefault"), fields(CardUpdateRequest(isDefault = false)).keys)
        assertEquals("false", fields(CardUpdateRequest(isDefault = false))["isDefault"]?.jsonPrimitive?.content)
        assertEquals(setOf("status"), fields(CardUpdateRequest(status = "INACTIVE")).keys)
    }

    @Test fun `별칭 지우기는 빈 문자열로 간다`() {
        assertEquals("", fields(CardUpdateRequest(nickname = ""))["nickname"]?.jsonPrimitive?.content)
    }

    @Test fun `순서 요청 — 필드 이름 그대로`() {
        assertEquals(setOf("userCardIds"), fields(CardOrderRequest(listOf(3, 1))).keys)
    }

    @Test fun `신용카드 상세`() {
        val card = YamuJson.decodeFromString<CardDetailDto>(creditJson).toInfo()
        assertFalse(card.isCheck)
        assertEquals("1021", card.last4)
        assertEquals(14, card.paymentDay)
        assertEquals(5L, card.settlementAccountId)
        assertEquals(NextWithdrawal(32_000, LocalDate.of(2026, 11, 14)), card.nextWithdrawal)
        assertTrue(card.recommended)
        assertTrue(card.isDefault)
        assertNull(card.stateLabel)
    }

    @Test fun `별칭이 없으면 서버가 상품명을 주는데 이건 별칭이 아니다`() {
        val card = YamuJson.decodeFromString<CardDetailDto>(creditJson).toInfo()
        assertNull(card.nickname)
        assertEquals("KB국민 청춘대로 톡톡카드", card.displayName)
    }

    @Test fun `체크카드 상세 — 연결 계좌 · 추천 제외 · 카드사 정지`() {
        val card = YamuJson.decodeFromString<CardDetailDto>(checkJson).toInfo()
        assertTrue(card.isCheck)
        assertEquals("생활비", card.displayName)
        assertEquals("신한은행 ·1234", card.checkAccountLabel)
        assertNull(card.paymentDay)
        assertNull(card.nextWithdrawal)
        assertFalse(card.recommended)
        assertTrue(card.issuerBlocked)
        assertEquals("카드사 정지", card.stateLabel)
    }

    @Test fun `목록에서 INACTIVE는 자동 선택에 포함되지 않은 카드`() {
        val dto = CardDto(1, null, IssuerDto(name = "KB국민카드"), "KB 카드", maskedNumber = "9410-****-****-1021")
        assertFalse(dto.copy(status = "INACTIVE").toLinked().recommended)
        assertTrue(dto.copy(status = "ACTIVE").toLinked().recommended)
    }

    @Test fun `목록의 별칭도 상품명과 같으면 없는 것으로`() {
        val dto = CardDto(1, "KB 카드", IssuerDto(name = "KB국민카드"), "KB 카드", maskedNumber = "9410-****-****-1021")
        assertNull(dto.toLinked().nickname)
        assertEquals("생활비", dto.copy(nickname = "생활비").toLinked().displayName)
    }

    @Test fun `해제 · 순서 오류 문장`() {
        assertEquals("출금이 끝나지 않은 청구가 있어 해제할 수 없어요", cardErrorMessage("CARD_HAS_OPEN_BILL"))
        assertEquals("다른 기기에서 카드가 바뀌었어요 · 순서를 다시 정해 주세요", cardErrorMessage("ORDER_INCOMPLETE"))
    }
}
