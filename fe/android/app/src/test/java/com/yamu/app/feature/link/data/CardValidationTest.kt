package com.yamu.app.feature.link.data

import androidx.compose.ui.text.AnnotatedString
import com.yamu.app.feature.link.CardNumberFormat
import com.yamu.app.feature.link.ExpiryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 카드 입력 규칙 · 오류 문장 — 서버(CardRegistrationService · CardLookupRequest)와 같은지 */
class CardValidationTest {
    private val today = LocalDate.of(2026, 11, 3)

    @Test fun `카드 번호는 숫자 16자리만`() {
        assertNull(CardValidation.number("9410123456785678"))
        assertNotNull(CardValidation.number(""))
        assertNotNull(CardValidation.number("941012345678567"))
        assertNotNull(CardValidation.number("94101234567856789"))
        assertNotNull(CardValidation.number("9410-1234-5678-56"))
    }

    @Test fun `유효기간 — 월은 01에서 12까지`() {
        assertNull(CardValidation.expiry("0829", today))
        assertNull(CardValidation.expiry("1229", today))
        assertNotNull(CardValidation.expiry("0029", today))
        assertNotNull(CardValidation.expiry("1329", today))
        assertNotNull(CardValidation.expiry("", today))
        assertNotNull(CardValidation.expiry("082", today))
    }

    @Test fun `유효기간 — 그 달 말일까지 유효`() {
        assertNull(CardValidation.expiry("1126", today))
        assertEquals("유효기간이 지난 카드예요", CardValidation.expiry("1026", today))
        assertEquals("유효기간이 지난 카드예요", CardValidation.expiry("1225", today))
    }

    @Test fun `별칭 — 앞뒤 공백을 빼고 비면 없음`() {
        assertNull(CardValidation.nickname(""))
        assertNull(CardValidation.nickname("   "))
        assertEquals("생활비", CardValidation.nickname("  생활비 "))
    }

    @Test fun `별칭 칸 — 12자를 넘는 새 글자는 받지 않고 붙여 넣은 글은 앞 12자만`() {
        val twelve = "가나다라마바사아자차카타"
        assertEquals(12, CardValidation.NICKNAME_MAX)
        assertEquals("생활비", CardValidation.nicknameInput("생활", "생활비"))
        assertEquals(twelve, CardValidation.nicknameInput("가나다라마바사아자차카", twelve))
        assertEquals(twelve, CardValidation.nicknameInput(twelve, twelve + "파"))
        assertEquals(twelve, CardValidation.nicknameInput("", twelve + "파하"))           // 붙여 넣기
        assertEquals("생활" + "가나다라마바사아자차", CardValidation.nicknameInput("생활", "생활" + twelve))
    }

    @Test fun `별칭 칸 — 예전에 길게 저장한 별칭은 지우기만 받는다`() {
        val old = "가나다라마바사아자차카타파하거너"                // 16자(서버는 20자까지 받았다)
        assertEquals(old.dropLast(1), CardValidation.nicknameInput(old, old.dropLast(1)))   // 한 글자 지우면 한 글자만 빠진다
        assertEquals(old, CardValidation.nicknameInput(old, old + "더"))
        assertTrue(CardValidation.nicknameTooLong(old))
        assertEquals("12자까지 쓸 수 있어요 · 4자를 줄여 주세요", CardValidation.nicknameError(old, overflowed = false))
    }

    @Test fun `별칭 칸 안내 — 글자 수, 넘기려 했을 때만 빨간 안내`() {
        assertEquals("0/12자", CardValidation.nicknameCount(""))
        assertEquals("3/12자", CardValidation.nicknameCount("생활비"))
        assertNull(CardValidation.nicknameError("생활비", overflowed = false))
        assertEquals("12자까지 쓸 수 있어요", CardValidation.nicknameError("가나다라마바사아자차카타", overflowed = true))
        assertFalse(CardValidation.nicknameTooLong("  가나다라마바사아자차카타  "))   // 앞뒤 공백은 저장 때 빠진다
    }

    @Test fun `카드 오류 문장`() {
        assertEquals("카드사에서 정지한 카드예요", cardErrorMessage("CARD_VERIFICATION_FAILED", "ISSUER_SUSPENDED"))
        assertEquals("유효기간이 끝난 카드예요", cardErrorMessage("CARD_VERIFICATION_FAILED", "ISSUER_EXPIRED"))
        assertEquals("유효기간이 지난 카드예요", cardErrorMessage("CARD_VERIFICATION_FAILED", "EXPIRY_PASSED"))
        assertEquals("유효기간을 확인해 주세요", cardErrorMessage("CARD_VERIFICATION_FAILED"))
        assertEquals("이미 등록한 카드예요", cardErrorMessage("CARD_ALREADY_REGISTERED"))
        assertNotNull(cardErrorMessage("CARD_NOT_FOUND"))
        assertNotNull(cardErrorMessage("INVALID_CARD_NUMBER"))
        assertNotNull(cardErrorMessage("ISSUER_NOT_SUPPORTED"))
        assertNotNull(cardErrorMessage("CARD_ACCOUNT_NOT_SET"))
        assertNotNull(cardErrorMessage("CARD_ACCOUNT_NOT_LINKED"))
        assertNotNull(cardErrorMessage("VERIFICATION_TOKEN_EXPIRED"))
        assertNull(cardErrorMessage("TOO_MANY_REQUESTS"))   // 가입 쪽 문장을 같이 쓴다
    }

    @Test fun `등록 실패 처리 — 만료는 입력 화면, 계좌는 연결, 이미 등록은 막기`() {
        assertEquals(CardRegisterFailure.RESTART, cardRegisterFailure("VERIFICATION_TOKEN_EXPIRED"))
        assertEquals(CardRegisterFailure.RESTART, cardRegisterFailure("CARD_VERIFICATION_FAILED"))
        assertEquals(CardRegisterFailure.RESTART, cardRegisterFailure("CARD_ACCOUNT_NOT_SET"))
        assertEquals(CardRegisterFailure.ACCOUNT_NOT_LINKED, cardRegisterFailure("CARD_ACCOUNT_NOT_LINKED"))
        assertEquals(CardRegisterFailure.ALREADY_REGISTERED, cardRegisterFailure("CARD_ALREADY_REGISTERED"))
        assertEquals(CardRegisterFailure.RETRY, cardRegisterFailure("NETWORK"))
    }

    @Test fun `카드 번호 표시 — 4자리씩 · 커서 위치가 범위를 넘지 않는다`() {
        (0..16).forEach { n ->
            val raw = "9".repeat(n)
            val t = CardNumberFormat.filter(AnnotatedString(raw))
            assertEquals(raw.chunked(4).joinToString(" "), t.text.text)
            (0..n).forEach { o ->
                val shown = t.offsetMapping.originalToTransformed(o)
                assertTrue("n=$n o=$o shown=$shown", shown in 0..t.text.length)
            }
            (0..t.text.length).forEach { o ->
                val back = t.offsetMapping.transformedToOriginal(o)
                assertTrue("n=$n o=$o back=$back", back in 0..n)
            }
            assertEquals(t.text.length, t.offsetMapping.originalToTransformed(n))
        }
    }

    @Test fun `유효기간 표시 — MM슬래시YY · 커서 위치가 범위를 넘지 않는다`() {
        assertEquals("08", ExpiryFormat.filter(AnnotatedString("08")).text.text)
        assertEquals("08/29", ExpiryFormat.filter(AnnotatedString("0829")).text.text)
        (0..4).forEach { n ->
            val t = ExpiryFormat.filter(AnnotatedString("1".repeat(n)))
            (0..n).forEach { o -> assertTrue(t.offsetMapping.originalToTransformed(o) in 0..t.text.length) }
            (0..t.text.length).forEach { o -> assertTrue(t.offsetMapping.transformedToOriginal(o) in 0..n) }
            assertEquals(t.text.length, t.offsetMapping.originalToTransformed(n))
        }
    }
}
