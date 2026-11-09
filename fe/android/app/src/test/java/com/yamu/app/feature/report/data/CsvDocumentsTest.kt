package com.yamu.app.feature.report.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** 저장 창에서 고른 문서에 쓰기 — "wt"를 받지 않는 저장소에선 "w"로 한 번 더 연다 */
class CsvDocumentsTest {
    private val bytes = "결제일시,가맹점\r\n".toByteArray()
    private val opened = mutableListOf<String>()
    private val written = ByteArrayOutputStream()

    /** [refuse]에 든 모드는 열지 못한다(예외), [nullFor]에 든 모드는 null */
    private fun opener(refuse: Set<String> = emptySet(), nullFor: Set<String> = emptySet()): (String) -> OutputStream? = { mode ->
        opened += mode
        when (mode) {
            in refuse -> throw FileNotFoundException("Unsupported mode: $mode")
            in nullFor -> null
            else -> written
        }
    }

    @Test fun `'wt'로 열리면 그대로 쓰고 'w'는 열지 않는다`() {
        assertTrue(writeTruncating(bytes, opener()))
        assertEquals(listOf("wt"), opened)
        assertArrayEquals(bytes, written.toByteArray())
    }

    @Test fun `'wt'를 받지 않는 저장소면 'w'로 한 번 더 열어 쓴다`() {
        assertTrue(writeTruncating(bytes, opener(refuse = setOf("wt"))))
        assertEquals(listOf("wt", "w"), opened)
        assertArrayEquals(bytes, written.toByteArray())
    }

    @Test fun `'wt'에서 스트림 없이 돌아와도 'w'로 연다`() {
        assertTrue(writeTruncating(bytes, opener(nullFor = setOf("wt"))))
        assertEquals(listOf("wt", "w"), opened)
    }

    @Test fun `둘 다 못 열면 false — 부르는 쪽이 크기 0인 파일만 지운다`() {
        assertFalse(writeTruncating(bytes, opener(refuse = setOf("wt"), nullFor = setOf("w"))))
        assertEquals(listOf("wt", "w"), opened)
    }

    @Test fun `연 뒤에 쓰다 실패하면 false이고 다시 열지 않는다`() {
        var closed = false
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("연결 끊김")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("연결 끊김")
            override fun close() { closed = true }
        }
        assertFalse(writeTruncating(bytes) { mode -> opened += mode; broken })
        assertEquals(listOf("wt"), opened)
        assertTrue(closed)   // 실패해도 닫는다
    }
}
