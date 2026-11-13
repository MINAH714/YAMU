package com.yamu.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 약관 전문(A-04a) — 원문 글 파일을 문단과 조 제목으로 나눈다(2차 QA 02) */
class TermDocTest {

    @Test fun `빈 줄로 문단을 나누고 조 제목 줄은 떼어 제목으로 둔다`() {
        val text = "야무 서비스 이용약관\r\n\r\n제1조(목적)\r\n이 약관은 이용 조건을 정합니다.\r\n\r\n제 2 조 (정의)\n① 서비스란 앱을 말합니다.\n② 회원이란 가입한 사람을 말합니다.\n\n\n부칙\n이 약관은 2026년 11월 13일부터 시행합니다."
        assertEquals(
            listOf(
                TermBlock("야무 서비스 이용약관", heading = false),
                TermBlock("제1조(목적)", heading = true),
                TermBlock("이 약관은 이용 조건을 정합니다.", heading = false),
                TermBlock("제 2 조 (정의)", heading = true),
                TermBlock("① 서비스란 앱을 말합니다.\n② 회원이란 가입한 사람을 말합니다.", heading = false),
                TermBlock("부칙", heading = true),
                TermBlock("이 약관은 2026년 11월 13일부터 시행합니다.", heading = false),
            ),
            termBlocks(text),
        )
    }

    @Test fun `조 제목만 있는 문단은 제목 하나로`() {
        assertEquals(listOf(TermBlock("제1장 총칙", heading = true)), termBlocks("\n제1장 총칙\n\n"))
    }

    @Test fun `본문 중간의 '제1조'나 '부칙에 따라'는 제목으로 보지 않는다`() {
        assertEquals(listOf(TermBlock("이 약관 제1조에 따라 정합니다.", heading = false)), termBlocks("이 약관 제1조에 따라 정합니다."))
        assertEquals(listOf(TermBlock("부칙에 따라 시행합니다.", heading = false)), termBlocks("부칙에 따라 시행합니다."))
        assertEquals(listOf(TermBlock("부칙 (2026. 11. 13.)", heading = true)), termBlocks("부칙 (2026. 11. 13.)"))
    }

    @Test fun `경로 값은 약관 이름 - 모르는 이름이면 없음`() {
        TermDoc.entries.forEach { assertEquals(it, TermDoc.of(it.name)) }
        assertNull(TermDoc.of("REFUND"))
    }

    /** 원문 파일(B-04 문안) — 자리 문장 · '[배포일]' · 노션 표기(굵게 · 표 · 목록 표시)가 화면에 그대로 나오지 않게 */
    @Test fun `원문 파일에는 자리 문장이나 노션 표기가 남지 않는다`() {
        val files = File("src/main/res/raw").listFiles { f -> f.name.startsWith("terms_") }.orEmpty()
        assertEquals(TermDoc.entries.size, files.size)
        files.forEach { f ->
            val text = f.readText()
            assertTrue(f.name, text.isNotBlank())
            listOf("넣을 자리", "[배포일]", "**", "|").forEach { assertFalse("${f.name}: $it", text.contains(it)) }
            assertFalse(f.name, text.lines().any { it.startsWith("- ") || it.startsWith("#") })
        }
    }

    @Test fun `서비스 이용약관은 제1조부터 제8조와 부칙이 제목으로 보인다`() {
        val headings = termBlocks(File("src/main/res/raw/terms_service.txt").readText()).filter { it.heading }.map { it.text }
        assertEquals((1..8).map { "제${it}조" } + "부칙", headings.map { it.substringBefore(' ') })
    }
}
