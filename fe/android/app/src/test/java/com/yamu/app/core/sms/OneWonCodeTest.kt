package com.yamu.app.core.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1원 입금 문자 → 숫자(09 문서 5-4) */
class OneWonCodeTest {
    @Test fun `은행 문자에서 YAMU 뒤 네 자리`() {
        assertEquals("1004", parseOneWonCode("[KB국민은행] 10/26 09:52 *1234 입금 1원 YAMU 1004", "YAMU", 4))
    }

    @Test fun `띄어쓰기 없거나 소문자여도 찾음`() {
        assertEquals("2580", parseOneWonCode("입금 1원 YAMU2580 잔액 950,001원", "YAMU", 4))
        assertEquals("2580", parseOneWonCode("입금 1원 yamu 2580", "YAMU", 4))
    }

    @Test fun `자리수가 다르면 채우지 않음`() {
        assertNull(parseOneWonCode("입금 1원 YAMU 10045", "YAMU", 4))
        assertNull(parseOneWonCode("입금 1원 YAMU 100", "YAMU", 4))
    }

    @Test fun `앞 글자가 없거나 빈 문자면 null`() {
        assertNull(parseOneWonCode("[KB국민은행] *1234 입금 1원 SSAFY 1004", "YAMU", 4))
        assertNull(parseOneWonCode(null, "YAMU", 4))
        assertEquals("456", parseOneWonCode("입금 1원 YAMU 456", "YAMU", 3))   // 서버가 3자리로 정하면 3자리
    }
}
